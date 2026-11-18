package com.jtalk.userservice.repository;

import com.jtalk.userservice.config.UserProperties;
import com.jtalk.userservice.domain.enums.GraphDirection;
import com.jtalk.userservice.domain.model.FollowEdge;
import io.lettuce.core.RedisFuture;
import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.async.RedisAsyncCommands;
import jakarta.inject.Singleton;

import java.time.Instant;
import java.util.ArrayList;
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
 * never reported. The marker also means a set is loaded exactly when its {@code ZCARD} is not zero, so a read never needs a separate
 * {@code EXISTS}.
 *
 * <p>Every read and write here is a batch: the commands go out together and the caller waits once, so an endpoint costs one Redis round
 * trip however many sets it touches. (The connection is shared between threads, so batching is done by having all the commands in
 * flight before waiting, never by switching auto-flush off.)
 */
@Singleton
public class SocialGraphRedisRepository extends RedisRepository {

    static final String SENTINEL = "-";
    private static final int BUILD_TTL_SECONDS = 300;
    /** A rebuild streams chunks without waiting for each; after this many in flight it waits, so a huge graph cannot queue unboundedly. */
    private static final int MAX_CHUNKS_IN_FLIGHT = 32;

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

    /**
     * What a profile page needs about one user, read in one round trip.
     *
     * @param followersLoaded  the followers set is in Redis; when false the counts and flags below mean nothing yet
     * @param followingLoaded  the following set is in Redis
     * @param followedByViewer the viewer is among the user's followers
     * @param followsViewer    the viewer is among the people the user follows
     */
    public record Stats(boolean followersLoaded, boolean followingLoaded, long followers, long following,
                        boolean followedByViewer, boolean followsViewer) {

        public boolean loaded() {
            return followersLoaded && followingLoaded;
        }
    }

    /** One page of a set, newest first, with the size of the whole set. {@code loaded} is false when the set is not in Redis. */
    public record Page(boolean loaded, long total, List<UUID> ids) {
    }

    /**
     * Where one user stands in another's set, read together with the set's size.
     *
     * @param loaded whether the set is in Redis; when false the other two mean nothing yet
     * @param member whether the other user is in the set
     * @param size   how many people the set holds
     */
    public record Membership(boolean loaded, boolean member, long size) {
    }

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

    /** The user's two sets and how the viewer relates to them: four commands, one round trip. */
    public Stats stats(UUID userId, UUID viewerId) {
        RedisAsyncCommands<String, String> redis = connection.async();
        String followers = key(userId, GraphDirection.FOLLOWERS);
        String following = key(userId, GraphDirection.FOLLOWING);
        RedisFuture<Long> followerCount = redis.zcard(followers);
        RedisFuture<Long> followingCount = redis.zcard(following);
        RedisFuture<Double> viewerFollows = redis.zscore(followers, viewerId.toString());
        RedisFuture<Double> viewerIsFollowed = redis.zscore(following, viewerId.toString());
        await(followerCount, followingCount, viewerFollows, viewerIsFollowed);
        long followersCard = followerCount.toCompletableFuture().join();
        long followingCard = followingCount.toCompletableFuture().join();
        return new Stats(followersCard > 0, followingCard > 0, Math.max(0, followersCard - 1), Math.max(0, followingCard - 1),
                viewerFollows.toCompletableFuture().join() != null, viewerIsFollowed.toCompletableFuture().join() != null);
    }

    /** Newest first. {@code offset} counts from the newest follow; O(log N + limit), and one round trip with the size. */
    public Page page(UUID ownerId, GraphDirection direction, long offset, int limit) {
        RedisAsyncCommands<String, String> redis = connection.async();
        String key = key(ownerId, direction);
        RedisFuture<Long> card = redis.zcard(key);
        RedisFuture<List<String>> members = redis.zrevrange(key, offset, offset + limit - 1);
        await(card, members);
        long cardinality = card.toCompletableFuture().join();
        List<UUID> ids = members.toCompletableFuture().join().stream()
                .filter(member -> !SENTINEL.equals(member))
                .map(UUID::fromString)
                .toList();
        return new Page(cardinality > 0, Math.max(0, cardinality - 1), ids);
    }

    /** O(1): whether the set is loaded, whether {@code otherId} is in it, and how big it is: two commands, one round trip. */
    public Membership membership(UUID ownerId, GraphDirection direction, UUID otherId) {
        RedisAsyncCommands<String, String> redis = connection.async();
        String key = key(ownerId, direction);
        RedisFuture<Long> card = redis.zcard(key);
        RedisFuture<Double> score = redis.zscore(key, otherId.toString());
        await(card, score);
        long cardinality = card.toCompletableFuture().join();
        return new Membership(cardinality > 0, score.toCompletableFuture().join() != null, Math.max(0, cardinality - 1));
    }

    /** Records a follow in both users' sets (each only if loaded) in one round trip. */
    public void recordFollow(UUID followerId, UUID targetId, Instant createdAt) {
        RedisAsyncCommands<String, String> redis = connection.async();
        String score = String.valueOf(createdAt.toEpochMilli());
        await(redis.eval(ADD_IF_LOADED, ScriptOutputType.INTEGER, new String[]{key(followerId, GraphDirection.FOLLOWING)},
                        score, targetId.toString()),
                redis.eval(ADD_IF_LOADED, ScriptOutputType.INTEGER, new String[]{key(targetId, GraphDirection.FOLLOWERS)},
                        score, followerId.toString()));
    }

    /** Removes a follow from both users' sets in one round trip. */
    public void recordUnfollow(UUID followerId, UUID targetId) {
        RedisAsyncCommands<String, String> redis = connection.async();
        await(redis.zrem(key(followerId, GraphDirection.FOLLOWING), targetId.toString()),
                redis.zrem(key(targetId, GraphDirection.FOLLOWERS), followerId.toString()));
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
        private final List<RedisFuture<?>> inFlight = new ArrayList<>();

        private Rebuild(String key) {
            this.key = key;
            this.buildKey = key + ":building:" + UUID.randomUUID();
        }

        /** Sends a chunk without waiting for Redis to take it, so reading the next chunk from PostgreSQL overlaps with this one. */
        public void add(List<FollowEdge> edges) {
            if (edges.isEmpty()) {
                return;
            }
            Object[] scoresAndMembers = new Object[edges.size() * 2];
            for (int i = 0; i < edges.size(); i++) {
                scoresAndMembers[2 * i] = (double) edges.get(i).createdAt().toEpochMilli();
                scoresAndMembers[2 * i + 1] = edges.get(i).userId().toString();
            }
            RedisAsyncCommands<String, String> redis = connection.async();
            if (inFlight.isEmpty()) {
                inFlight.add(redis.expire(buildKey, BUILD_TTL_SECONDS));   // an abandoned build cleans itself up
            }
            inFlight.add(redis.zadd(buildKey, scoresAndMembers));
            if (inFlight.size() >= MAX_CHUNKS_IN_FLIGHT) {
                await(inFlight.toArray(RedisFuture<?>[]::new));
                inFlight.clear();
            }
        }

        /** Makes the set visible, replacing whatever was there. Commands on one connection run in order, so the rename follows every chunk. */
        public void commit() {
            RedisAsyncCommands<String, String> redis = connection.async();
            inFlight.add(redis.zadd(buildKey, 0d, SENTINEL));
            inFlight.add(redis.rename(buildKey, key));
            inFlight.add(redis.expire(key, ttlSeconds));
            await(inFlight.toArray(RedisFuture<?>[]::new));
            inFlight.clear();
        }
    }
}
