package com.jtalk.userservice;

import com.jtalk.userservice.support.ApiTestBase;
import io.micronaut.context.annotation.Property;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.client.exceptions.HttpClientResponseException;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Property(name = "user.max-languages", value = "3")
class LanguageApiTest extends ApiTestBase {

    private Map<String, Object> learn(String user, String code, String level) {
        return post("/api/v1/users/me/languages", user, level == null
                ? Map.of("languageCode", code, "languageType", "LEARNING")
                : Map.of("languageCode", code, "languageType", "LEARNING", "proficiencyLevel", level));
    }

    @Test
    void theMatrixListsNativeAndLearningLanguagesWithLevels() {
        register("alice", "en");

        assertEquals(List.of("en"), get("/api/v1/users/me/languages", "alice").get("nativeLanguages"), "the profile's native language is in it from the start");

        learn("alice", "ar", "INTERMEDIATE");
        Map<String, Object> matrix = learn("alice", "es", null);

        assertEquals(List.of("en"), matrix.get("nativeLanguages"));
        assertEquals(List.of(Map.of("languageCode", "ar", "proficiencyLevel", "INTERMEDIATE"),
                Map.of("languageCode", "es", "proficiencyLevel", "BEGINNER")), matrix.get("learningLanguages"), "no level means beginner");
        assertEquals(matrix, get(userPath("alice", "/languages"), "bob"), "anyone can read it");

        // Listing it again changes the level instead of adding a second row.
        Map<String, Object> promoted = learn("alice", "ar", "ADVANCED");
        assertEquals("ADVANCED", ((Map<?, ?>) ((List<?>) promoted.get("learningLanguages")).getFirst()).get("proficiencyLevel"));
        assertEquals(2, ((List<?>) promoted.get("learningLanguages")).size());
    }

    @Test
    void aLanguageIsNativeOrLearningNeverBoth_andTheMatrixIsBounded() {
        register("alice", "en");
        learn("alice", "ar", null);

        assertProblem(HttpStatus.CONFLICT, "language-conflict", () -> learn("alice", "en", null));
        assertProblem(HttpStatus.CONFLICT, "language-conflict",
                () -> post("/api/v1/users/me/languages", "alice", Map.of("languageCode", "ar", "languageType", "NATIVE")));

        learn("alice", "fr", null);   // en + ar + fr = the 3 this test allows
        assertProblem(HttpStatus.UNPROCESSABLE_ENTITY, "too-many-languages", () -> learn("alice", "de", null));
        learn("alice", "fr", "FLUENT");   // changing one already listed is not adding
    }

    @Test
    void concurrentAddsCannotExceedTheLanguageLimit() throws Exception {
        register("alice", "en");
        learn("alice", "fr", null);
        List<String> languages = List.of("ar", "es", "de", "pt", "it", "ja", "ko", "zh");
        var start = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<HttpStatus>> requests = languages.stream().map(language -> executor.submit(() -> {
                start.await();
                try {
                    learn("alice", language, null);
                    return HttpStatus.OK;
                } catch (HttpClientResponseException rejected) {
                    return rejected.getStatus();
                }
            })).toList();
            start.countDown();

            List<HttpStatus> statuses = new java.util.ArrayList<>();
            for (var request : requests) {
                statuses.add(request.get(10, TimeUnit.SECONDS));
            }
            assertEquals(1, statuses.stream().filter(HttpStatus.OK::equals).count());
            assertEquals(languages.size() - 1, statuses.stream().filter(HttpStatus.UNPROCESSABLE_ENTITY::equals).count());
        }
        assertEquals(2, ((List<?>) get("/api/v1/users/me/languages", "alice").get("learningLanguages")).size());
    }

    @Test
    void aLanguageCanBeRemovedExceptThePrimaryNativeOne() {
        register("alice", "en");
        learn("alice", "ar", null);

        Map<String, Object> afterRemoval = http.retrieve(HttpRequest.DELETE("/api/v1/users/me/languages/ar").bearerAuth(token("alice")), MAP);
        assertEquals(List.of(), afterRemoval.get("learningLanguages"));

        assertProblem(HttpStatus.UNPROCESSABLE_ENTITY, "primary-language-required",
                () -> http.exchange(HttpRequest.DELETE("/api/v1/users/me/languages/en").bearerAuth(token("alice"))));
        assertProblem(HttpStatus.NOT_FOUND, "language-not-found",
                () -> http.exchange(HttpRequest.DELETE("/api/v1/users/me/languages/ja").bearerAuth(token("alice"))));
    }

    @Test
    void matchmakingPairsPeopleWhoCanTeachEachOther() {
        register("alice", "en");
        learn("alice", "ar", null);

        register("bob", "ar");        // native in what alice learns, learning what she speaks: a match
        learn("bob", "en", "BEGINNER");
        register("gina", "ar");       // also a match
        learn("gina", "en", "ADVANCED");
        register("carol", "ar");      // speaks Arabic but wants French: not a match
        learn("carol", "fr", null);
        register("dave", "en");       // same side as alice
        learn("dave", "ar", null);
        register("erin", "ar");       // a match whom alice has blocked
        learn("erin", "en", null);
        register("frank", "ar");      // a match who blocked alice
        learn("frank", "en", null);
        block("alice", "erin");
        block("frank", "alice");

        Map<String, Object> page = get("/api/v1/users/matchmaking", "alice");
        assertEquals(List.of(id("gina").toString(), id("bob").toString()), contentIds(page),
                "bob and gina; newest first; not carol (wrong language), dave (same side), erin or frank (blocked), nor alice herself");
        assertFalse((Boolean) page.get("hasNext"));
        assertNull(page.get("nextAfterId"));

        // The partner from the other side sees alice, and only alice.
        assertEquals(List.of(id("alice").toString()), contentIds(get("/api/v1/users/matchmaking", "bob")).stream()
                .filter(id -> id.equals(id("alice").toString())).toList());
    }

    @Test
    void matchmakingIsKeysetPaginated_andTheLanguagesCanBeChosenInTheRequest() {
        register("alice", "en");
        learn("alice", "ar", null);
        for (String name : List.of("pal1", "pal2", "pal3")) {
            register(name, "ar");
            learn(name, "en", null);
        }

        Map<String, Object> first = get("/api/v1/users/matchmaking?size=2", "alice");
        assertEquals(2, contentIds(first).size());
        assertTrue((Boolean) first.get("hasNext"));

        Map<String, Object> second = get("/api/v1/users/matchmaking?size=2&afterCreatedAt=" + first.get("nextAfterCreatedAt")
                + "&afterId=" + first.get("nextAfterId"), "alice");
        assertEquals(1, contentIds(second).size());
        assertFalse((Boolean) second.get("hasNext"));
        assertEquals(3, java.util.stream.Stream.concat(contentIds(first).stream(), contentIds(second).stream()).distinct().count(),
                "no user twice and none skipped across the page boundary");

        // Explicit languages override the caller's own: someone native in French who is learning Arabic.
        register("fifi", "fr");
        learn("fifi", "ar", null);
        assertEquals(List.of(id("fifi").toString()), contentIds(get("/api/v1/users/matchmaking?nativeLang=ar&learningLang=fr", "alice")));
    }

    @Test
    void matchmakingNeedsALanguageToMatchOn() {
        register("alice", "en");

        assertProblem(HttpStatus.UNPROCESSABLE_ENTITY, "no-learning-language", () -> get("/api/v1/users/matchmaking", "alice"));
        assertEquals(List.of(), contentIds(get("/api/v1/users/matchmaking?learningLang=ar", "alice")), "naming it is enough");
    }

    @SuppressWarnings("unchecked")
    private static List<String> contentIds(Map<String, Object> page) {
        return ((List<Map<String, Object>>) page.get("content")).stream().map(user -> (String) user.get("id")).toList();
    }
}
