package com.jtalk.userservice.repository;

import com.jtalk.userservice.config.UserProperties;
import com.jtalk.userservice.domain.entity.User;
import com.jtalk.userservice.domain.enums.UserStatus;
import io.lettuce.core.RedisFuture;
import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.async.RedisAsyncCommands;
import io.micronaut.core.io.ResourceResolver;
import jakarta.inject.Singleton;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Profiles cached as Redis hashes, {@code user:profile:{userId}}, so rendering a room means one pipelined round trip for all its
 * participants instead of one database query each. A hash is stored with {@code put_profile_if_newer.lua}: a profile is only
 * replaced by a newer one, so a reader that loaded an old copy can never overwrite what a writer has just stored.
 *
 * <p>Changes are also announced on {@link #CHANNEL} so other services can drop what they cached about the user.
 */
@Singleton
public class UserRedisRepository extends RedisRepository {

    /** Redis pub/sub channel carrying one JSON message per profile change: {@code {"type","userId","version"}}. */
    public static final String CHANNEL = "user-events";

    private static final String VERSION = "version";

    private final LuaScript putIfNewer;
    private final long ttlSeconds;

    public UserRedisRepository(StatefulRedisConnection<String, String> connection, ResourceResolver resolver, UserProperties properties) {
        super(connection);
        this.putIfNewer = LuaScript.load(resolver, "lua/put_profile_if_newer.lua");
        this.ttlSeconds = properties.profileCacheTtl().toSeconds();
    }

    public Optional<User> find(UUID userId) {
        return Optional.ofNullable(findAll(List.of(userId)).get(userId));
    }

    /** The cached profiles among {@code userIds}, read in one pipelined round trip; users that are not cached are absent. */
    public Map<UUID, User> findAll(Collection<UUID> userIds) {
        RedisAsyncCommands<String, String> redis = connection.async();
        Map<UUID, RedisFuture<Map<String, String>>> reads = new LinkedHashMap<>();
        userIds.forEach(id -> reads.put(id, redis.hgetall(key(id))));
        await(reads.values().toArray(RedisFuture<?>[]::new));

        Map<UUID, User> found = new HashMap<>();
        reads.forEach((id, read) -> fromHash(id, read.toCompletableFuture().join()).ifPresent(user -> found.put(id, user)));
        return found;
    }

    /** @return true if the profile was stored, false if Redis already held a copy that is as new or newer */
    public boolean put(User user) {
        List<String> args = new ArrayList<>();
        args.add(String.valueOf(ttlSeconds));
        args.add(String.valueOf(version(user)));
        toHash(user).forEach((field, value) -> {
            args.add(field);
            args.add(value);
        });
        Long stored = putIfNewer.execute(connection.sync(), ScriptOutputType.INTEGER, new String[]{key(user.id())}, args.toArray(String[]::new));
        return stored != null && stored == 1;
    }

    public void putAll(Collection<User> users) {
        users.forEach(this::put);
    }

    /** Tells every subscriber that this user's profile changed. */
    public void announceUpdated(User user) {
        connection.sync().publish(CHANNEL,
                "{\"type\":\"USER_PROFILE_UPDATED\",\"userId\":\"%s\",\"version\":%d}".formatted(user.id(), version(user)));
    }

    static String key(UUID userId) {
        return userKey("profile", userId);
    }

    /** The profile's updatedAt in epoch microseconds, which is the precision PostgreSQL keeps: two changes never share a version. */
    static long version(User user) {
        return ChronoUnit.MICROS.between(Instant.EPOCH, user.updatedAt());
    }

    private static Map<String, String> toHash(User user) {
        Map<String, String> hash = new LinkedHashMap<>();
        hash.put("username", user.username());
        hash.put("displayName", user.displayName());
        if (user.bio() != null) {
            hash.put("bio", user.bio());
        }
        if (user.avatarUrl() != null) {
            hash.put("avatarUrl", user.avatarUrl());
        }
        hash.put("countryCode", user.countryCode());
        hash.put("nativeLanguage", user.nativeLanguage());
        hash.put("status", user.status().name());
        hash.put("createdAt", String.valueOf(ChronoUnit.MICROS.between(Instant.EPOCH, user.createdAt())));
        hash.put(VERSION, String.valueOf(version(user)));
        return hash;
    }

    /** A hash that is missing or unreadable counts as not cached: the caller reloads the profile and stores it again. */
    private static Optional<User> fromHash(UUID userId, Map<String, String> hash) {
        if (hash == null || hash.isEmpty()) {
            return Optional.empty();
        }
        try {
            return Optional.of(new User(userId, hash.get("username"), hash.get("displayName"), hash.get("bio"), hash.get("avatarUrl"),
                    hash.get("countryCode"), hash.get("nativeLanguage"), UserStatus.valueOf(hash.get("status")),
                    Instant.EPOCH.plus(Long.parseLong(hash.get("createdAt")), ChronoUnit.MICROS),
                    Instant.EPOCH.plus(Long.parseLong(hash.get(VERSION)), ChronoUnit.MICROS)));
        } catch (RuntimeException unreadable) {
            return Optional.empty();
        }
    }
}
