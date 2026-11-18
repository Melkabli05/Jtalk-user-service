package com.jtalk.userservice;

import com.jtalk.userservice.support.ApiTestBase;
import io.micronaut.http.HttpStatus;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class SocialGraphApiTest extends ApiTestBase {

    private static String following(String name) {
        return "user:following:{" + id(name) + "}";
    }

    private static String followers(String name) {
        return "user:followers:{" + id(name) + "}";
    }

    @Test
    void followingIsIdempotentAndShowsInBothUsersNumbers() {
        register("alice");
        register("bob");

        assertEquals(HttpStatus.NO_CONTENT, follow("alice", "bob").status());
        assertEquals(HttpStatus.NO_CONTENT, follow("alice", "bob").status(), "following again succeeds and changes nothing");

        assertEquals(Map.of("followerCount", 1, "followingCount", 0, "followedByMe", true, "followsMe", false), social("alice", "bob"));
        assertEquals(Map.of("followerCount", 0, "followingCount", 1, "followedByMe", false, "followsMe", true), social("bob", "alice"));
        assertEquals(Map.of("followerCount", 1, "followingCount", 0, "followedByMe", false, "followsMe", false), social("bob", "bob"),
                "about yourself the flags are false");
        assertEquals(1, userRepository.count() - 1, "two users");
    }

    @Test
    void theListsAreNewestFirstAndPaged() {
        for (String name : List.of("alice", "bob", "carol", "dave", "target")) {
            register(name);
        }
        for (String name : List.of("alice", "bob", "carol", "dave")) {
            follow(name, "target");
        }

        Map<String, Object> firstPage = get(userPath("target", "/followers?size=3"), "alice");
        assertEquals(ids("dave", "carol", "bob"), listedIds(firstPage), "the latest follower first");
        assertEquals(4, firstPage.get("total"));
        assertEquals(0, firstPage.get("page"));
        assertEquals(ids("alice"), listedIds(get(userPath("target", "/followers?size=3&page=1"), "alice")));
        assertEquals(List.of(), listedIds(get(userPath("target", "/followers?size=3&page=2"), "alice")));

        follow("target", "alice");
        follow("target", "bob");
        assertEquals(ids("bob", "alice"), listedIds(get(userPath("target", "/following"), "carol")));
        assertEquals(ids("target"), listedIds(get(userPath("alice", "/following"), "carol")));
        assertEquals("bob", ((List<Map<String, Object>>) get(userPath("target", "/following"), "carol").get("content")).getFirst().get("displayName"),
                "each entry is a profile summary, not a bare id");
    }

    @Test
    void unfollowingRemovesTheFollowFromBothSidesAndIsIdempotent() {
        register("alice");
        register("bob");
        follow("alice", "bob");

        assertEquals(HttpStatus.NO_CONTENT, unfollow("alice", "bob").status());
        assertEquals(HttpStatus.NO_CONTENT, unfollow("alice", "bob").status());

        assertEquals(Map.of("followerCount", 0, "followingCount", 0, "followedByMe", false, "followsMe", false), social("alice", "bob"));
        assertEquals(List.of(), listedIds(get(userPath("bob", "/followers"), "alice")));
        assertEquals(List.of(), listedIds(get(userPath("alice", "/following"), "alice")));
    }

    @Test
    void youCannotFollowYourselfOrSomeoneWhoDoesNotExist() {
        register("alice");

        assertProblem(HttpStatus.UNPROCESSABLE_ENTITY, "cannot-follow-self", () -> follow("alice", "alice"));
        assertProblem(HttpStatus.NOT_FOUND, "user-not-found", () -> follow("alice", "ghost"));
        assertProblem(HttpStatus.NOT_FOUND, "user-not-found", () -> follow("ghost", "alice"));
        assertProblem(HttpStatus.NOT_FOUND, "user-not-found", () -> social("alice", "ghost"));
    }

    @Test
    void theSetsAreFilledOnFirstUseAndKeptCurrentByWritesAfterThat() {
        register("alice");
        register("bob");

        // A user nobody follows still gets a (marked) set, so the next read is not another database query.
        assertEquals(0, social("alice", "bob").get("followerCount"));
        assertNotNull(redis.sync().zscore(followers("bob"), "-"));

        // Once loaded, a follow is written through to both sets without a rebuild.
        follow("alice", "bob");
        assertNotNull(redis.sync().zscore(following("alice"), id("bob").toString()));
        assertNotNull(redis.sync().zscore(followers("bob"), id("alice").toString()));
        assertEquals(2, redis.sync().zcard(followers("bob")), "alice and the marker");
        assertEquals(1, social("alice", "bob").get("followerCount"));

        unfollow("alice", "bob");
        assertNull(redis.sync().zscore(following("alice"), id("bob").toString()));
        assertNull(redis.sync().zscore(followers("bob"), id("alice").toString()));
    }

    @Test
    void aFollowIsNeverAddedToASetThatIsNotLoaded() {
        register("alice");
        register("bob");
        redis.sync().flushall();

        follow("alice", "bob");   // loads alice's following (empty), then adds; bob's followers were never loaded

        assertEquals(0, redis.sync().exists(followers("bob")), "a set that holds one member would look complete");
        assertEquals(1, social("alice", "bob").get("followerCount"), "and when it is read it is rebuilt, with the follow in it");
    }

    @Test
    void anEmptyOrLostRedisIsRebuiltFromPostgres() {
        for (String name : List.of("alice", "bob", "carol")) {
            register(name);
        }
        follow("alice", "carol");
        follow("bob", "carol");
        follow("carol", "alice");
        Map<String, Object> before = get(userPath("carol", "/followers"), "alice");

        redis.sync().flushall();

        assertEquals(before, get(userPath("carol", "/followers"), "alice"), "same followers, same order, same total");
        assertEquals(Map.of("followerCount", 2, "followingCount", 1, "followedByMe", true, "followsMe", true), social("alice", "carol"));
    }

    @Test
    void aLargeGraphIsRebuiltInChunks() {
        register("star");
        // Enough followers to need more than one 1000-row chunk when the set is rebuilt.
        for (int i = 0; i < 1100; i++) {
            String name = "fan" + i;
            userRepository.save(new com.jtalk.userservice.domain.entity.User(id(name), username(name), name, null, null, "MA", "en",
                    com.jtalk.userservice.domain.enums.UserStatus.ACTIVE, null, null));
        }
        // The service writes the follows (rather than a bulk insert) so each one gets its own timestamp.
        redis.sync().flushall();
        for (int i = 0; i < 1100; i++) {
            follow("fan" + i, "star");
        }
        redis.sync().flushall();

        Map<String, Object> page = get(userPath("star", "/followers?size=100&page=10"), "fan0");

        assertEquals(1100, page.get("total"));
        assertEquals(100, listedIds(page).size());
        assertEquals(id("fan0").toString(), listedIds(page).getLast(), "the oldest follower is last");
    }

    @Test
    void blockingRemovesFollowsBothWaysAndForbidsNewOnes() {
        register("alice");
        register("bob");
        follow("alice", "bob");
        follow("bob", "alice");
        social("alice", "bob");   // both users' sets are loaded and cached

        assertEquals(HttpStatus.NO_CONTENT, block("alice", "bob").status());
        assertEquals(HttpStatus.NO_CONTENT, block("alice", "bob").status(), "blocking again succeeds");

        assertEquals(Map.of("followerCount", 0, "followingCount", 0, "followedByMe", false, "followsMe", false), social("alice", "bob"),
                "neither follow survives, and the cached sets were dropped, not left showing them");
        assertProblem(HttpStatus.FORBIDDEN, "follow-not-allowed", () -> follow("alice", "bob"));
        assertProblem(HttpStatus.FORBIDDEN, "follow-not-allowed", () -> follow("bob", "alice"));
        assertEquals(ids("bob"), getList("/api/v1/users/me/blocks", "alice").stream().map(u -> (String) u.get("id")).toList());
        assertEquals(List.of(), getList("/api/v1/users/me/blocks", "bob"), "the blocked user is not told, and has no block of their own");

        assertEquals(HttpStatus.NO_CONTENT, send(io.micronaut.http.HttpRequest.DELETE(userPath("bob", "/block")), "alice").status());
        assertEquals(HttpStatus.NO_CONTENT, follow("bob", "alice").status(), "after unblocking, following works again");
        assertEquals(List.of(), getList("/api/v1/users/me/blocks", "alice"));
    }

    @Test
    void youCannotBlockYourselfOrNobody() {
        register("alice");

        assertProblem(HttpStatus.UNPROCESSABLE_ENTITY, "cannot-block-self", () -> block("alice", "alice"));
        assertProblem(HttpStatus.NOT_FOUND, "user-not-found", () -> block("alice", "ghost"));
    }
}
