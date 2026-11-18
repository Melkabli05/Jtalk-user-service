package com.jtalk.userservice;

import com.jtalk.userservice.support.ApiTestBase;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.client.exceptions.HttpClientResponseException;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Secure by default: every API call needs a bearer token whose subject is a UUID, signed with the shared secret. */
class SecurityApiTest extends ApiTestBase {

    private HttpStatus statusOf(HttpRequest<?> request) {
        return assertThrows(HttpClientResponseException.class, () -> http.exchange(request)).getStatus();
    }

    @Test
    void callsWithoutAValidTokenAreRefused() {
        register("alice");

        assertEquals(HttpStatus.UNAUTHORIZED, statusOf(HttpRequest.GET(userPath("alice", ""))), "no token");
        assertEquals(HttpStatus.UNAUTHORIZED, statusOf(HttpRequest.GET("/api/v1/users/me").bearerAuth("not-a-jwt")));
        assertEquals(HttpStatus.UNAUTHORIZED,
                statusOf(HttpRequest.GET("/api/v1/users/me").bearerAuth(tokenWithSubject(id("alice").toString(), "another-secret-another-secret-0123456789"))),
                "signed with another secret");
        assertEquals(HttpStatus.UNAUTHORIZED,
                statusOf(HttpRequest.GET("/api/v1/users/me").bearerAuth(tokenWithSubject("alice", "test-jwt-secret-test-jwt-secret-0123456789"))),
                "a subject that is not a UUID");
        assertEquals(HttpStatus.UNAUTHORIZED, statusOf(HttpRequest.POST("/api/v1/users/batch", Map.of("ids", java.util.List.of(id("alice"))))));
    }

    @Test
    void theCallerIsTheTokensSubjectAndNobodyElse() {
        register("alice");
        register("bob");

        assertEquals(id("alice").toString(), get("/api/v1/users/me", "alice").get("id"));
        assertEquals(id("bob").toString(), get("/api/v1/users/me", "bob").get("id"));
    }

    @Test
    void healthProbesAnswerWithoutATokenAndHideTheDetails() {
        String liveness = http.retrieve(HttpRequest.GET("/health/liveness"));
        assertTrue(liveness.contains("UP"), liveness);
        String readiness = http.retrieve(HttpRequest.GET("/health/readiness"));
        assertTrue(readiness.contains("UP") && !readiness.contains("jdbc"), readiness);
    }
}
