package com.jtalk.userservice;

import com.jtalk.userservice.support.ApiTestBase;
import io.micronaut.context.annotation.Property;
import io.micronaut.http.HttpStatus;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** A profile and its languages are written together: one request, one transaction. */
@Property(name = "user.max-languages", value = "4")
class ProfileLanguagesApiTest extends ApiTestBase {

    private static Map<String, Object> learning(String code, String level) {
        return level == null ? Map.of("languageCode", code) : Map.of("languageCode", code, "proficiencyLevel", level);
    }

    private Map<String, Object> create(String name, String nativeLanguage, List<Map<String, Object>> learning) {
        return post("/api/v1/users/me", name, Map.of("username", username(name), "displayName", name, "nativeLanguage", nativeLanguage,
                "learningLanguages", learning));
    }

    private Map<String, Object> update(String name, Map<String, Object> change) {
        return patch("/api/v1/users/me", name, change);
    }

    private static List<Map<String, Object>> entries(Map<String, Object> profile) {
        return List.of(((List<?>) profile.get("learningLanguages")).toArray(Map[]::new));
    }

    @Test
    void aProfileIsCreatedWithItsLanguagesInOneRequest() {
        Map<String, Object> created = create("alice", "en", List.of(learning("ar", "INTERMEDIATE"), learning("fr", null)));

        assertEquals(List.of("en"), created.get("nativeLanguages"));
        assertEquals(List.of(Map.of("languageCode", "ar", "proficiencyLevel", "INTERMEDIATE"),
                Map.of("languageCode", "fr", "proficiencyLevel", "BEGINNER")), entries(created));
        assertEquals(created.get("learningLanguages"), get("/api/v1/users/me", "alice").get("learningLanguages"), "and it is what is stored");
        assertEquals(get("/api/v1/users/me/languages", "alice").get("learningLanguages"), created.get("learningLanguages"));
        // Ready to be matched straight away: no second request was needed to say what she is learning.
        get("/api/v1/users/matchmaking", "alice");
    }

    @Test
    void anotherPersonsProfileCarriesTheirLanguagesToo() {
        create("alice", "en", List.of(learning("ar", "ADVANCED")));
        register("bob", "ar");

        Map<String, Object> seen = get(userPath("alice", ""), "bob");
        assertEquals(List.of("en"), seen.get("nativeLanguages"));
        assertEquals(List.of(Map.of("languageCode", "ar", "proficiencyLevel", "ADVANCED")), entries(seen));
        assertEquals(List.of(), get("/api/v1/users/me", "bob").get("learningLanguages"), "empty lists stay in the payload");
    }

    @Test
    void theListOfLearningLanguagesIsReplacedAsAWhole_orLeftAloneWhenAbsent() {
        create("alice", "en", List.of(learning("ar", "BEGINNER"), learning("fr", "BEGINNER")));

        Map<String, Object> changed = update("alice", Map.of("learningLanguages", List.of(learning("ar", "ADVANCED"), learning("de", null))));
        assertEquals(List.of(Map.of("languageCode", "ar", "proficiencyLevel", "ADVANCED"),
                Map.of("languageCode", "de", "proficiencyLevel", "BEGINNER")), entries(changed), "fr is gone, de is new, ar has a new level");

        Map<String, Object> renamed = update("alice", Map.of("displayName", "Alice B"));
        assertEquals(entries(changed), entries(renamed), "a request without the list does not touch it");

        assertEquals(List.of(), entries(update("alice", Map.of("learningLanguages", List.of()))), "an empty list clears it");
    }

    @Test
    void nativeAndLearningCanBeSwappedInOneSave() {
        create("alice", "ar", List.of(learning("en", "BEGINNER")));

        Map<String, Object> swapped = update("alice", Map.of("nativeLanguage", "en", "learningLanguages", List.of(learning("ar", "FLUENT"))));

        assertEquals("en", swapped.get("nativeLanguage"));
        assertEquals(List.of("en"), swapped.get("nativeLanguages"));
        assertEquals(List.of(Map.of("languageCode", "ar", "proficiencyLevel", "FLUENT")), entries(swapped));
    }

    @Test
    void aRequestThatFailsChangesNothing() {
        create("alice", "en", List.of(learning("ar", "BEGINNER")));

        // The native language cannot also be learned.
        assertProblem(HttpStatus.CONFLICT, "language-conflict", () -> update("alice",
                Map.of("displayName", "Changed", "learningLanguages", List.of(learning("en", null)))));
        // en + ar + fr + de + es = 5, one more than this test allows.
        assertProblem(HttpStatus.UNPROCESSABLE_ENTITY, "too-many-languages", () -> update("alice",
                Map.of("displayName", "Changed", "learningLanguages", List.of(learning("fr", null), learning("de", null), learning("es", null), learning("ar", null)))));

        Map<String, Object> unchanged = get("/api/v1/users/me", "alice");
        assertEquals("alice", unchanged.get("displayName"), "the name in the failed requests was not saved");
        assertEquals(List.of(Map.of("languageCode", "ar", "proficiencyLevel", "BEGINNER")), entries(unchanged));
    }

    @Test
    void aProfileWhoseLanguagesAreInvalidIsNotCreatedAtAll() {
        assertProblem(HttpStatus.CONFLICT, "language-conflict", () -> create("alice", "en", List.of(learning("en", null))));

        assertProblem(HttpStatus.NOT_FOUND, "user-not-found", () -> get("/api/v1/users/me", "alice"));
    }

    @Test
    void aBadLanguageCodeIsRejectedBeforeItReachesTheDatabase() {
        assertProblem(HttpStatus.BAD_REQUEST, null, () -> create("alice", "en", List.of(learning("English", null))));
    }

    @SuppressWarnings("unchecked")
    private List<String> partners(String name, String query) {
        return ((List<Map<String, Object>>) get("/api/v1/users/matchmaking" + query, name).get("content")).stream()
                .map(user -> (String) user.get("id")).toList();
    }

    @Test
    void matchmakingConsidersEveryLanguageThePersonLearnsAndSpeaks() {
        create("alice", "en", List.of(learning("ar", null), learning("fr", null)));   // fr sorts after ar: it used to be ignored
        post("/api/v1/users/me/languages", "alice", Map.of("languageCode", "es", "languageType", "NATIVE"));   // a second native language

        create("arabic", "ar", List.of(learning("en", null)));    // native in one she learns, learning what she speaks
        create("french", "fr", List.of(learning("es", null)));    // native in the other one, learning her second native language
        create("german", "de", List.of(learning("en", null)));    // speaks neither of the two
        create("wrongway", "fr", List.of(learning("it", null)));  // native French, but wants Italian: nothing in common
        create("both", "ar", List.of(learning("en", null), learning("es", null)));   // matches twice over and is still listed once

        assertEquals(ids("both", "french", "arabic"), partners("alice", ""), "newest first, each person once");
        assertEquals(ids("both", "arabic"), partners("alice", "?learningLang=ar"), "naming a language narrows it to that one");
    }
}
