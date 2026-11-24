package com.jtalk.userservice;

import com.jtalk.userservice.domain.entity.User;
import com.jtalk.userservice.domain.enums.UserStatus;
import com.jtalk.userservice.support.ApiTestBase;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class ProfileApiTest extends ApiTestBase {

    @Test
    void createReadAndPartiallyUpdateAProfile() {
        HttpResponse<Map<String, Object>> created = http.exchange(HttpRequest.POST("/api/v1/users/me", Map.of(
                "username", "sara.k", "displayName", "  Sara K  ", "bio", "I like tea", "avatarUrl", "https://cdn.example.com/sara.png",
                "countryCode", "MA", "nativeLanguage", "ar")).bearerAuth(token("sara")), MAP);

        assertEquals(HttpStatus.CREATED, created.status());
        assertEquals("/api/v1/users/" + id("sara"), created.header("Location"));
        Map<String, Object> profile = created.body();
        assertEquals(id("sara").toString(), profile.get("id"), "the id is the token's subject, never the body's");
        assertEquals("Sara K", profile.get("displayName"), "surrounding blanks are stripped");
        assertNotNull(profile.get("createdAt"));

        // Anyone can read it, and "me" is the same profile.
        assertEquals(profile, get(userPath("sara", ""), "someone"));
        assertEquals(profile, get("/api/v1/users/me", "sara"));

        // A partial update: absent fields keep their value, "" clears an optional one, the username never changes.
        Map<String, Object> updated = patch("/api/v1/users/me", "sara", Map.of("displayName", "Sara", "bio", ""));
        assertEquals("Sara", updated.get("displayName"));
        assertNull(updated.get("bio"), "an empty bio clears it");
        assertEquals("https://cdn.example.com/sara.png", updated.get("avatarUrl"));
        assertEquals("sara.k", updated.get("username"));
        assertEquals(profile.get("createdAt"), updated.get("createdAt"));
        assertEquals(updated, get(userPath("sara", ""), "someone"), "the next read sees the change");
    }

    @Test
    void changingTheNativeLanguageKeepsTheLanguageMatrixInStep() {
        register("sara", "ar");
        post("/api/v1/users/me/languages", "sara", Map.of("languageCode", "fr", "languageType", "LEARNING", "proficiencyLevel", "ADVANCED"));

        patch("/api/v1/users/me", "sara", Map.of("nativeLanguage", "fr"));

        Map<String, Object> matrix = get("/api/v1/users/me/languages", "sara");
        assertEquals(List.of("ar", "fr"), matrix.get("nativeLanguages"), "French was being learned; now it is native too");
        assertEquals(List.of(), matrix.get("learningLanguages"));
    }

    @Test
    void aSecondProfileOrATakenUsernameIsAConflict() {
        register("sara");

        assertProblem(HttpStatus.CONFLICT, "profile-already-exists", () -> register("sara"));
        assertProblem(HttpStatus.CONFLICT, "username-taken", () -> post("/api/v1/users/me", "omar",
                Map.of("username", username("sara"), "displayName", "Omar", "countryCode", "MA", "nativeLanguage", "ar")));
    }

    @Test
    void invalidInputIsRejectedBeforeItReachesTheDatabase() {
        Map<String, Object> valid = Map.of("username", "omar", "displayName", "Omar", "countryCode", "MA", "nativeLanguage", "ar");
        for (Map<String, Object> bad : List.of(
                with(valid, "username", "Omar"),                                  // uppercase
                with(valid, "username", "om"),                                    // too short
                with(valid, "username", "omar omar"),
                with(valid, "displayName", "   "),
                with(valid, "countryCode", "Morocco"),
                with(valid, "nativeLanguage", "arabic"),
                with(valid, "avatarUrl", "javascript:alert(1)"),
                with(valid, "avatarUrl", "http://insecure.example.com/a.png"),
                with(valid, "avatarUrl", "//evil.example.com/a.png"),            // protocol-relative: would load from another host
                with(valid, "avatarUrl", "/../secret.png"),
                with(valid, "bio", "x".repeat(301)))) {
            assertProblemOrValidation(bad);
        }
        assertEquals(HttpStatus.CREATED, http.exchange(HttpRequest.POST("/api/v1/users/me", valid).bearerAuth(token("omar"))).status());
    }

    private void assertProblemOrValidation(Map<String, Object> body) {
        var ex = org.junit.jupiter.api.Assertions.assertThrows(io.micronaut.http.client.exceptions.HttpClientResponseException.class,
                () -> http.exchange(HttpRequest.POST("/api/v1/users/me", body).bearerAuth(token("omar"))));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus(), body.toString());
    }

    private static Map<String, Object> with(Map<String, Object> base, String key, Object value) {
        Map<String, Object> copy = new HashMap<>(base);
        copy.put(key, value);
        return copy;
    }

    @Test
    void aProfileNeedsNoCountry_andMayUseAnAppPathAsItsPicture() {
        Map<String, Object> created = post("/api/v1/users/me", "nour", Map.of("username", "nour", "displayName", "Nour",
                "avatarUrl", "/avatars/avatar-03.svg", "nativeLanguage", "ar"));

        assertNull(created.get("countryCode"), "no country was given");
        assertEquals("/avatars/avatar-03.svg", created.get("avatarUrl"));
        assertEquals(created, get(userPath("nour", ""), "someone"), "read back from the cache, which holds no country field either");
        redis.sync().flushall();
        assertEquals(created, get(userPath("nour", ""), "someone"), "and from the database");

        Map<String, Object> changed = patch("/api/v1/users/me", "nour", Map.of("countryCode", "MA"));
        assertEquals("MA", changed.get("countryCode"));
    }

    private void registerAs(String user, String username, String displayName) {
        post("/api/v1/users/me", user, Map.of("username", username, "displayName", displayName, "nativeLanguage", "en"));
    }

    private List<String> found(String query, String asUser) {
        return getList("/api/v1/users/search?" + query, asUser).stream().map(u -> (String) u.get("username")).toList();
    }

    @Test
    void searchRanksHandleMatchesBeforeDisplayNameMatches_whateverTheirOrder() {
        register("viewer");
        registerAs("u1", "aaron", "Samir A");   // matches by display name, and its handle sorts first
        registerAs("u2", "sam2", "Q");           // matches by handle

        assertEquals(List.of("sam2", "aaron"), found("q=sam", "viewer"), "the handle someone typed beats a name that merely starts the same");
    }

    @Test
    void searchWorksInAnyScript() {
        register("viewer");
        registerAs("u1", "mona9", "منى");

        assertEquals(List.of("mona9"), found("q=%D9%85%D9%86", "viewer"), "an Arabic prefix finds the Arabic display name");
        assertEquals(List.of(), found("q=%D9%85%D9%86%D9%89%D8%A7", "viewer"), "and a longer prefix than the name does not");
    }

    @Test
    void searchIsCappedAtThePageAndNeverRepeatsAPerson() {
        register("viewer");
        for (int i = 1; i <= 5; i++) {
            registerAs("u" + i, "sam" + i, "Sam " + i);   // each matches by handle AND by display name
        }

        List<String> page = found("q=sam&size=3", "viewer");
        List<String> all = found("q=sam&size=50", "viewer");

        assertEquals(List.of("sam1", "sam2", "sam3"), page);
        assertEquals(List.of("sam1", "sam2", "sam3", "sam4", "sam5"), all, "no one appears twice for matching both ways");
    }

    @Test
    void searchTreatsWhatTheUserTypedAsText_notAsAPattern() {
        register("viewer");
        registerAs("u1", "sam_x", "Sam");

        assertEquals(List.of("sam_x"), found("q=sam_", "viewer"));
        assertEquals(List.of(), found("q=sa_", "viewer"), "an underscore is a character, not a wildcard");
        assertEquals(List.of(), found("q=%25", "viewer"));
        assertEquals(List.of(), found("q=%5C", "viewer"), "nor is a backslash");
    }

    @Test
    void anUnknownUserIsA404Problem() {
        assertProblem(HttpStatus.NOT_FOUND, "user-not-found", () -> get(userPath("ghost", ""), "sara"));
        assertProblem(HttpStatus.NOT_FOUND, "user-not-found", () -> get("/api/v1/users/me", "ghost"));
        assertProblem(HttpStatus.NOT_FOUND, "user-not-found", () -> patch("/api/v1/users/me", "ghost", Map.of("displayName", "Ghost")));
    }

    @Test
    void aBatchResolvesARoomInTheOrderAskedAndSkipsUnknownIds() {
        register("alice");
        register("bob");
        register("carol");

        List<Map<String, Object>> batch = http.retrieve(HttpRequest.POST("/api/v1/users/batch", Map.of("ids",
                List.of(id("carol"), id("ghost"), id("alice"), id("carol"), id("bob")))).bearerAuth(token("alice")), MAP_LIST);

        assertEquals(ids("carol", "alice", "bob"), batch.stream().map(u -> (String) u.get("id")).toList(),
                "asked order, the duplicate and the unknown id dropped");
        assertEquals("carol", batch.getFirst().get("displayName"));
        assertFalse(batch.getFirst().containsKey("bio"), "a summary carries no bio");
    }

    @Test
    void aBatchIsBoundedAndNeverEmpty() {
        List<UUID> tooMany = IntStream.range(0, 501).mapToObj(i -> UUID.randomUUID()).toList();
        assertProblemOrBadRequest(Map.of("ids", tooMany));
        assertProblemOrBadRequest(Map.of("ids", List.of()));
    }

    private void assertProblemOrBadRequest(Map<String, Object> body) {
        var ex = org.junit.jupiter.api.Assertions.assertThrows(io.micronaut.http.client.exceptions.HttpClientResponseException.class,
                () -> http.exchange(HttpRequest.POST("/api/v1/users/batch", body).bearerAuth(token("alice"))));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
    }

    @Test
    void searchFindsActiveUsersByUsernameOrDisplayNamePrefix() {
        register("sarah");
        register("sam");
        register("viewer");
        post("/api/v1/users/me", "zed", Map.of("username", "zed", "displayName", "Samira Z", "countryCode", "MA", "nativeLanguage", "ar"));

        follow("viewer", "sarah");
        follow("sam", "viewer");
        var found = getList("/api/v1/users/search?q=SA", "viewer");
        assertEquals(List.of("sam", "sarah", "zed"), found.stream().map(user -> user.get("username")).toList());
        assertEquals(List.of(false, true, false), found.stream().map(user -> user.get("followedByMe")).toList());

        assertEquals(List.of("sarah"), getList("/api/v1/users/search?q=sara", "viewer").stream().map(u -> (String) u.get("username")).toList());
        assertEquals(List.of(), getList("/api/v1/users/search?q=%25", "viewer"), "LIKE wildcards are plain characters");
        assertEquals(List.of(), getList("/api/v1/users/search?q=sa_", "viewer"));
        assertEquals(List.of("sam"), getList("/api/v1/users/search?q=sa&size=1", "viewer").stream().map(u -> (String) u.get("username")).toList());

        // Never the caller, never someone suspended, never someone either side has blocked.
        assertEquals(List.of("sarah", "zed"), getList("/api/v1/users/search?q=sa", "sam").stream().map(u -> (String) u.get("username")).toList());
        User sam = userRepository.findById(id("sam")).orElseThrow();
        userRepository.update(new User(sam.id(), sam.username(), sam.displayName(), sam.bio(), sam.avatarUrl(), sam.countryCode(),
                sam.nativeLanguage(), UserStatus.SUSPENDED, sam.createdAt(), sam.updatedAt()));
        block("viewer", "zed");
        assertEquals(List.of("sarah"), getList("/api/v1/users/search?q=sa", "viewer").stream().map(u -> (String) u.get("username")).toList());
        assertEquals(List.of(), getList("/api/v1/users/search?q=vie", "zed"), "a block hides both ways");
    }
}
