package com.jtalk.userservice;

import com.jtalk.userservice.domain.entity.User;
import com.jtalk.userservice.repository.UserRedisRepository;
import com.jtalk.userservice.support.ApiTestBase;
import io.lettuce.core.RedisClient;
import io.lettuce.core.pubsub.RedisPubSubAdapter;
import io.lettuce.core.pubsub.StatefulRedisPubSubConnection;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The Redis side of profiles: what is cached, when it is replaced, and what other services are told. */
class ProfileCacheTest extends ApiTestBase {

    @Inject
    UserRedisRepository cache;

    @Inject
    RedisClient redisClient;

    private static String key(String name) {
        return "user:profile:{" + id(name) + "}";
    }

    @Test
    void aReadFillsTheCacheAndAnEmptyCacheIsNotAnError() {
        register("alice");
        redis.sync().flushall();   // registering already cached it

        Map<String, Object> first = get(userPath("alice", ""), "bob");
        Map<String, String> hash = redis.sync().hgetall(key("alice"));
        assertEquals("alice", hash.get("displayName"), "the miss was loaded from PostgreSQL and cached");
        assertTrue(redis.sync().ttl(key("alice")) > 23 * 3600, "for about a day");

        redis.sync().flushall();
        assertEquals(first, get(userPath("alice", ""), "bob"), "losing the cache costs a query, not correctness");
    }

    @Test
    void aBatchOfColdUsersIsCachedWithOneRead() {
        for (String name : List.of("alice", "bob", "carol")) {
            register(name);
        }
        redis.sync().flushall();

        http.retrieve(io.micronaut.http.HttpRequest.POST("/api/v1/users/batch", Map.of("ids", List.of(id("alice"), id("bob"), id("carol"))))
                .bearerAuth(token("alice")), MAP_LIST);

        for (String name : List.of("alice", "bob", "carol")) {
            assertEquals(name, redis.sync().hget(key(name), "displayName"));
        }
    }

    @Test
    void anUpdateReplacesTheCachedCopyAtOnce_andClearedFieldsDisappearFromIt() {
        post("/api/v1/users/me", "alice", Map.of("username", "alice", "displayName", "Alice", "bio", "hello",
                "avatarUrl", "https://cdn.example.com/a.png", "countryCode", "MA", "nativeLanguage", "en"));
        assertEquals("hello", redis.sync().hget(key("alice"), "bio"));

        patch("/api/v1/users/me", "alice", Map.of("displayName", "Alice B", "bio", ""));

        assertEquals("Alice B", redis.sync().hget(key("alice"), "displayName"), "written through after the commit, not left to expire");
        assertNull(redis.sync().hget(key("alice"), "bio"), "a cleared field does not survive from the old copy");
        assertEquals("https://cdn.example.com/a.png", redis.sync().hget(key("alice"), "avatarUrl"));
    }

    @Test
    void aStaleCopyNeverReplacesANewerOne() {
        register("alice");
        patch("/api/v1/users/me", "alice", Map.of("displayName", "Newer"));
        User current = userRepository.findById(id("alice")).orElseThrow();
        assertEquals("Newer", redis.sync().hget(key("alice"), "displayName"));

        // A slow reader that loaded the profile before the update now reaches Redis with what it read.
        User older = new User(current.id(), current.username(), "Older", current.bio(), current.avatarUrl(), current.countryCode(),
                current.nativeLanguage(), current.status(), current.createdAt(), current.updatedAt().minusSeconds(5));
        assertFalse(cache.put(older), "an older version is refused");
        assertFalse(cache.put(current), "so is the same version");
        assertEquals("Newer", redis.sync().hget(key("alice"), "displayName"));

        User newer = new User(current.id(), current.username(), "Newest", current.bio(), current.avatarUrl(), current.countryCode(),
                current.nativeLanguage(), current.status(), current.createdAt(), current.updatedAt().plusSeconds(5));
        assertTrue(cache.put(newer));
        assertEquals("Newest", redis.sync().hget(key("alice"), "displayName"));
    }

    @Test
    void otherServicesAreToldWhenAProfileChanges() throws Exception {
        List<String> messages = new CopyOnWriteArrayList<>();
        try (StatefulRedisPubSubConnection<String, String> subscriber = redisClient.connectPubSub()) {
            subscriber.addListener(new RedisPubSubAdapter<>() {
                @Override
                public void message(String channel, String message) {
                    messages.add(channel + " " + message);
                }
            });
            subscriber.sync().subscribe(UserRedisRepository.CHANNEL);

            register("alice");
            patch("/api/v1/users/me", "alice", Map.of("displayName", "Alice B"));
            await("two profile announcements", Duration.ofSeconds(5).toMillis(), () -> messages.size() >= 2);
        }

        assertTrue(messages.stream().allMatch(m -> m.startsWith("user-events {\"type\":\"USER_PROFILE_UPDATED\",\"userId\":\"" + id("alice") + "\",\"version\":")),
                messages.toString());
    }
}
