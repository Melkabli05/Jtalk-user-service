package com.jtalk.userservice.repository;

import com.jtalk.userservice.config.UserProperties;
import com.jtalk.userservice.domain.entity.User;
import com.jtalk.userservice.domain.enums.UserStatus;
import io.lettuce.core.RedisFuture;
import io.lettuce.core.RedisNoScriptException;
import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.async.RedisAsyncCommands;
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
 * participants instead of one database query each. A hash is stored by {@link #PUT_IF_NEWER}: a profile is only
 * replaced by a newer one, so a reader that loaded an old copy can never overwrite what a writer has just stored.
 *
 * <p>Changes are also announced on {@link #CHANNEL} so other services can drop what they cached about the user.
 */
@Singleton
public class UserRedisRepository extends RedisRepository {

    /** Redis pub/sub channel carrying one JSON message per profile change: {@code {"type","userId","version"}}. */
    public static final String CHANNEL = "user-events";

    private static final String VERSION = "version";

    /**
     * Stores a profile hash unless Redis already holds a copy that is as new or newer.
     *
     * <p>A slow reader that loaded a profile before it was changed can reach Redis after the writer has already stored the new one; without
     * this check it would put the old one back for a day. Versions are the profile's updatedAt in epoch microseconds.
     * KEYS[1] the hash; ARGV[1] ttl in seconds; ARGV[2] version; ARGV[3..] field, value pairs. Returns 1 if stored, 0 if the cached copy was kept.
     * The hash is replaced, not merged, so a field that was cleared (the avatar) does not survive from the old copy.
     */
    private static final String PUT_IF_NEWER = """
            local current = redis.call('HGET', KEYS[1], 'version')
            if current and tonumber(current) >= tonumber(ARGV[2]) then
                return 0
            end
            redis.call('DEL', KEYS[1])
            redis.call('HSET', KEYS[1], unpack(ARGV, 3))
            redis.call('EXPIRE', KEYS[1], ARGV[1])
            return 1
            """;

    private final long ttlSeconds;
    /** SHA-1 Redis gave the script when it was loaded; null until first needed, and again after Redis forgets it. */
    private volatile String putDigest;

    public UserRedisRepository(StatefulRedisConnection<String, String> connection, UserProperties properties) {
        super(connection);
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
        Long stored = connection.sync().eval(PUT_IF_NEWER, ScriptOutputType.INTEGER, new String[]{key(user.id())}, scriptArguments(user));
        return stored != null && stored == 1;
    }

    /**
     * Caches many profiles in one round trip (a cold room fills in everyone it lacked). The script is loaded once and called by its
     * digest, so a batch of 200 sends 200 short commands together instead of waiting for 200 answers one by one.
     */
    public void putAll(Collection<User> users) {
        if (users.size() <= 1) {
            users.forEach(this::put);
            return;
        }
        RedisAsyncCommands<String, String> redis = connection.async();
        String digest = putDigest();
        List<RedisFuture<?>> writes = new ArrayList<>(users.size());
        for (User user : users) {
            writes.add(redis.evalsha(digest, ScriptOutputType.INTEGER, new String[]{key(user.id())}, scriptArguments(user)));
        }
        try {
            await(writes.toArray(RedisFuture<?>[]::new));
        } catch (RuntimeException failure) {
            if (!isUnknownScript(failure)) {
                throw failure;
            }
            putDigest = null;   // Redis restarted or flushed its scripts: the next batch loads it again, and this one is written the slow way
            users.forEach(this::put);
        }
    }

    private String putDigest() {
        String digest = putDigest;
        if (digest == null) {
            digest = connection.sync().scriptLoad(PUT_IF_NEWER);
            putDigest = digest;
        }
        return digest;
    }

    private static boolean isUnknownScript(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof RedisNoScriptException) {
                return true;
            }
        }
        return false;
    }

    /** TTL, version, then the hash's field/value pairs: what {@link #PUT_IF_NEWER} expects after its key. */
    private String[] scriptArguments(User user) {
        List<String> args = new ArrayList<>();
        args.add(String.valueOf(ttlSeconds));
        args.add(String.valueOf(version(user)));
        toHash(user).forEach((field, value) -> {
            args.add(field);
            args.add(value);
        });
        return args.toArray(String[]::new);
    }

    /** Tells every subscriber that this user's profile changed. */
    public void announceUpdated(User user) {
        connection.sync().publish(CHANNEL,
                "{\"type\":\"USER_PROFILE_UPDATED\",\"userId\":\"%s\",\"version\":%d}".formatted(user.id(), version(user)));
    }

    /** Drops the cached profile of a user whose account was deleted. */
    public void evict(UUID userId) {
        connection.sync().del(key(userId));
    }

    /** Tells every subscriber that this user is gone, so they drop what they cached about them. */
    public void announceDeleted(UUID userId) {
        connection.sync().publish(CHANNEL, "{\"type\":\"USER_DELETED\",\"userId\":\"%s\"}".formatted(userId));
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
        if (user.countryCode() != null) {
            hash.put("countryCode", user.countryCode());
        }
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
