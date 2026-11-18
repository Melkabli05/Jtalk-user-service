package com.jtalk.userservice.repository;

import com.jtalk.userservice.config.UserProperties;
import com.jtalk.userservice.domain.enums.GraphDirection;
import com.jtalk.userservice.domain.model.FollowEdge;
import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import jakarta.inject.Singleton;

import java.time.Instant;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * The follow graph cached as sorted sets: {@code user:following:{id}} and {@code user:followers:{id}}, member = the other user's id,
 * score = when the follow was made (epoch millis). PostgreSQL stays the source of truth; a set that is not here is rebuilt from it.
 *
 * <p>A set is only ever complete or absent: it is built in a temporary key and renamed into place, and a follow is added only to a set
 * that exists ({@link #ADD_IF_LOADED}). That is what makes "does A follow B" one {@code ZSCORE} and a count one {@code ZCARD}.
 * A graph with no one in it still needs a key, so every set carries a {@link #SENTINEL} member with score 0, which sorts last and is
 * never reported.
 */
@Singleton
public class SocialGraphRedisRepository extends RedisRepository {

    static final String SENTINEL = "-";
    private static final int BUILD_TTL_SECONDS = 300;

    /**
     * Adds a member to a set only if that set is in Redis. A set that is not there is rebuilt from PostgreSQL the next time it is read, and
     * that rebuild already includes this follow, so adding to a missing key would only create a set that looks complete but holds one member.
     * NX keeps an existing member's original timestamp. KEYS[1] the set; ARGV[1] score (epoch millis); ARGV[2] member.
     */
    private static final String ADD_IF_LOADED = """
            if redis.call('EXISTS', KEYS[1]) == 1 then
                return redis.call('ZADD', KEYS[1], 'NX', ARGV[1], ARGV[2])
            end
            return 0
            """;

    private final long ttlSeconds;

    public SocialGraphRedisRepository(StatefulRedisConnection<String, String> connection, UserProperties properties) {
        super(connection);
        this.ttlSeconds = properties.graphCacheTtl().toSeconds();
    }

    public boolean isLoaded(UUID ownerId, GraphDirection direction) {
        return connection.sync().exists(key(ownerId, direction)) > 0;
    }

    /** Starts building a set from PostgreSQL; nothing is visible to readers until {@link Rebuild#commit()}. */
    public Rebuild startRebuild(UUID ownerId, GraphDirection direction) {
        return new Rebuild(key(ownerId, direction));
    }

    /** Records a follow in a set that is loaded; a set that is not loaded already gets it when it is rebuilt. */
    public void addIfLoaded(UUID ownerId, GraphDirection direction, UUID otherId, Instant createdAt) {
        connection.sync().eval(ADD_IF_LOADED, ScriptOutputType.INTEGER, new String[]{key(ownerId, direction)},
                String.valueOf(createdAt.toEpochMilli()), otherId.toString());
    }

    public void remove(UUID ownerId, GraphDirection direction, UUID otherId) {
        connection.sync().zrem(key(ownerId, direction), otherId.toString());
    }

    /** O(1): whether {@code otherId} is in the owner's set. Only meaningful once the set is loaded. */
    public boolean contains(UUID ownerId, GraphDirection direction, UUID otherId) {
        return connection.sync().zscore(key(ownerId, direction), otherId.toString()) != null;
    }

    /** O(1). Only meaningful once the set is loaded. */
    public long size(UUID ownerId, GraphDirection direction) {
        return Math.max(0, connection.sync().zcard(key(ownerId, direction)) - 1);
    }

    /** Newest first. {@code offset} counts from the newest follow; O(log N + limit). */
    public List<UUID> page(UUID ownerId, GraphDirection direction, long offset, int limit) {
        return connection.sync().zrevrange(key(ownerId, direction), offset, offset + limit - 1).stream()
                .filter(member -> !SENTINEL.equals(member))
                .map(UUID::fromString)
                .toList();
    }

    /** Drops both of each user's sets; they are rebuilt on demand. */
    public void evict(Collection<UUID> userIds) {
        String[] keys = userIds.stream()
                .flatMap(id -> Arrays.stream(GraphDirection.values()).map(direction -> key(id, direction)))
                .toArray(String[]::new);
        if (keys.length > 0) {
            connection.sync().del(keys);
        }
    }

    static String key(UUID ownerId, GraphDirection direction) {
        return userKey(direction == GraphDirection.FOLLOWING ? "following" : "followers", ownerId);
    }

    /** A set being filled in chunks, so rebuilding a large graph never holds all of it in memory. */
    public final class Rebuild {

        private final String key;
        private final String buildKey;
        private boolean started;

        private Rebuild(String key) {
            this.key = key;
            this.buildKey = key + ":building:" + UUID.randomUUID();
        }

        public void add(List<FollowEdge> edges) {
            if (edges.isEmpty()) {
                return;
            }
            Object[] scoresAndMembers = new Object[edges.size() * 2];
            for (int i = 0; i < edges.size(); i++) {
                scoresAndMembers[2 * i] = (double) edges.get(i).createdAt().toEpochMilli();
                scoresAndMembers[2 * i + 1] = edges.get(i).userId().toString();
            }
            RedisCommands<String, String> redis = connection.sync();
            redis.zadd(buildKey, scoresAndMembers);
            if (!started) {
                started = true;
                redis.expire(buildKey, BUILD_TTL_SECONDS);   // an abandoned build cleans itself up
            }
        }

        /** Makes the set visible, replacing whatever was there. */
        public void commit() {
            RedisCommands<String, String> redis = connection.sync();
            redis.zadd(buildKey, 0d, SENTINEL);
            redis.rename(buildKey, key);
            redis.expire(key, ttlSeconds);
        }
    }
}
