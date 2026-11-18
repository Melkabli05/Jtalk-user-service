package com.jtalk.userservice;

import com.jtalk.userservice.support.ApiTestBase;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpStatus;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Deleting an account takes the profile and everything that hangs off it, and leaves other people's numbers right. */
class AccountDeletionApiTest extends ApiTestBase {

    private HttpStatus delete(String who) {
        return send(HttpRequest.DELETE("/api/v1/users/me"), who).status();
    }

    @Test
    void theProfileIsGoneForTheirOwnReadsAndForEveryoneElses() {
        register("alice");
        register("bob");

        assertEquals(HttpStatus.NO_CONTENT, delete("alice"));

        assertProblem(HttpStatus.NOT_FOUND, "user-not-found", () -> get("/api/v1/users/me", "alice"));
        assertProblem(HttpStatus.NOT_FOUND, "user-not-found", () -> get(userPath("alice", ""), "bob"));
        assertEquals(List.of(), getList("/api/v1/users/search?q=" + username("alice"), "bob"));
    }

    @Test
    void theirFollowsGoAndOtherPeoplesNumbersAreRead_notRemembered() {
        register("alice");
        register("bob");
        register("carol");
        follow("alice", "bob");
        follow("carol", "alice");
        follow("bob", "carol");
        // Everyone's numbers are read once, so they are cached and a deletion has something to correct.
        assertEquals(1, social("carol", "bob").get("followerCount"));
        assertEquals(1, social("bob", "carol").get("followerCount"));

        delete("alice");

        assertEquals(0, social("carol", "bob").get("followerCount"), "bob lost the follower that was alice");
        assertEquals(0, social("bob", "carol").get("followingCount"), "carol no longer follows anyone");
        assertEquals(1, social("bob", "carol").get("followerCount"), "carol keeps the follow from bob");
    }

    @Test
    void theirBlocksGoToo_bothThoseTheyMadeAndThoseMadeAgainstThem() {
        register("alice");
        register("bob");
        register("carol");
        block("alice", "bob");
        block("carol", "alice");

        delete("alice");

        assertEquals(List.of(), getList("/api/v1/users/me/blocks", "carol"));
        assertEquals(List.of(), getList("/api/v1/users/me/blocks", "bob"));
    }

    @Test
    void theirLanguagesAreGone_soTheyAreNoLongerAMatch() {
        register("alice", "ar");
        register("bob", "en");
        // A match goes both ways: she speaks what he learns, and she learns what he speaks.
        post("/api/v1/users/me/languages", "bob", Map.of("languageCode", "ar", "languageType", "LEARNING"));
        post("/api/v1/users/me/languages", "alice", Map.of("languageCode", "en", "languageType", "LEARNING"));
        assertEquals(1, ((List<?>) get("/api/v1/users/matchmaking", "bob").get("content")).size());

        delete("alice");

        assertEquals(0, ((List<?>) get("/api/v1/users/matchmaking", "bob").get("content")).size());
    }

    @Test
    void askingAgainIsNotAnError_andTheyCanSignUpAgainWithTheSameHandle() {
        register("alice");

        assertEquals(HttpStatus.NO_CONTENT, delete("alice"));
        assertEquals(HttpStatus.NO_CONTENT, delete("alice"));
        assertEquals(HttpStatus.NO_CONTENT, delete("nobody-ever-signed-up"));

        Map<String, Object> again = register("alice");
        assertEquals(username("alice"), again.get("username"));
        assertTrue(((List<?>) again.get("learningLanguages")).isEmpty(), "a fresh start, nothing carried over");
    }

    @Test
    void onlyYourOwnAccountCanBeDeletedAndOnlyWhenSignedIn() {
        register("alice");

        assertProblem(HttpStatus.UNAUTHORIZED, null, () -> http.exchange(HttpRequest.DELETE("/api/v1/users/me")));
        assertEquals(HttpStatus.NO_CONTENT, delete("bob"), "bob has no account: nothing of alice's is touched");
        assertEquals(username("alice"), get("/api/v1/users/me", "alice").get("username"));
    }
}
