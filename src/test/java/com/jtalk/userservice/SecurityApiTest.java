package com.jtalk.userservice;

import com.auth0.jwt.JWT;
import com.auth0.jwt.JWTCreator;
import com.auth0.jwt.algorithms.Algorithm;
import com.jtalk.userservice.support.ApiTestBase;
import com.jtalk.userservice.support.FakeAuth0;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.client.exceptions.HttpClientResponseException;
import org.junit.jupiter.api.Test;

import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Secure by default: every API call needs an Auth0 access token. The service checks its RS256 signature against the tenant's JWKS, its
 * issuer, its audience, its expiry, and that it names the user by a UUID in the custom claim. All of that is configuration of Micronaut
 * Security; these tests make sure it stays switched on and pointed at the right things.
 */
class SecurityApiTest extends ApiTestBase {

    private static final String SOMEONE = "alice";

    private int statusWith(String bearer) {
        var request = bearer == null ? HttpRequest.GET("/api/v1/users/me") : HttpRequest.GET("/api/v1/users/me").bearerAuth(bearer);
        return assertThrows(HttpClientResponseException.class, () -> http.exchange(request)).getStatus().getCode();
    }

    private static JWTCreator.Builder good() {
        return FakeAuth0.baseToken("auth0|" + id(SOMEONE)).withClaim(FakeAuth0.USER_ID_CLAIM, id(SOMEONE).toString());
    }

    @Test
    void anAuth0TokenIsAcceptedAndNamesTheCallerByItsUserIdClaimNotBySub() {
        register(SOMEONE);
        register("bob");

        assertEquals(id(SOMEONE).toString(), get("/api/v1/users/me", SOMEONE).get("id"));
        assertEquals(id("bob").toString(), get("/api/v1/users/me", "bob").get("id"));
        // The subject is "auth0|<uuid>", which is not a UUID: only the custom claim decides who the caller is.
        assertEquals(HttpStatus.OK, http.exchange(HttpRequest.GET("/api/v1/users/me").bearerAuth(FakeAuth0.sign(good()))).status());
    }

    @Test
    void callsWithoutAValidTokenAreRefused() {
        register(SOMEONE);

        assertEquals(401, statusWith(null), "no token");
        assertEquals(401, statusWith("not-a-jwt"));
        assertEquals(401, statusWith(FakeAuth0.signWithAnotherKey(good())), "signed with a key that is not in the tenant's JWKS");
        assertEquals(401, statusWith(FakeAuth0.sign(good().withAudience("https://some-other-api"))), "issued for another API");
        assertEquals(401, statusWith(FakeAuth0.sign(good().withIssuer("https://evil.example.com/"))), "issued by someone else");
        assertEquals(401, statusWith(FakeAuth0.sign(good().withExpiresAt(new Date(System.currentTimeMillis() - 60_000)))), "expired");
        assertEquals(401, statusWith(FakeAuth0.sign(FakeAuth0.baseToken("auth0|" + id(SOMEONE)))), "no user-id claim");
        assertEquals(401, statusWith(FakeAuth0.sign(FakeAuth0.baseToken("auth0|x").withClaim(FakeAuth0.USER_ID_CLAIM, "alice"))),
                "a user-id claim that is not a UUID");
        assertEquals(401, statusWith(JWT.create().withIssuer(FakeAuth0.ISSUER).withAudience(FakeAuth0.AUDIENCE)
                        .withClaim(FakeAuth0.USER_ID_CLAIM, id(SOMEONE).toString()).withExpiresAt(new Date(System.currentTimeMillis() + 60_000))
                        .sign(Algorithm.HMAC256("a-shared-secret-a-shared-secret-0123456789"))),
                "an HS256 token: a symmetric secret must not be accepted where only the tenant's public key is trusted");
    }

    @Test
    void everyEndpointRequiresAToken() {
        for (HttpRequest<?> request : List.of(
                HttpRequest.POST("/api/v1/users/me", Map.of()),
                HttpRequest.PATCH("/api/v1/users/me", Map.of()),
                HttpRequest.GET("/api/v1/users/" + UUID.randomUUID()),
                HttpRequest.POST("/api/v1/users/batch", Map.of("ids", List.of(UUID.randomUUID()))),
                HttpRequest.GET("/api/v1/users/search?q=a"),
                HttpRequest.GET("/api/v1/users/matchmaking"),
                HttpRequest.POST("/api/v1/users/" + UUID.randomUUID() + "/follow", ""),
                HttpRequest.GET("/api/v1/users/" + UUID.randomUUID() + "/followers"),
                HttpRequest.POST("/api/v1/users/" + UUID.randomUUID() + "/block", ""),
                HttpRequest.GET("/api/v1/users/me/blocks"))) {
            assertEquals(HttpStatus.UNAUTHORIZED,
                    assertThrows(HttpClientResponseException.class, () -> http.exchange(request)).getStatus(), request.getUri().toString());
        }
    }

    @Test
    void healthProbesAnswerWithoutATokenAndHideTheDetails() {
        String liveness = http.retrieve(HttpRequest.GET("/health/liveness"));
        assertTrue(liveness.contains("UP"), liveness);
        String readiness = http.retrieve(HttpRequest.GET("/health/readiness"));
        assertTrue(readiness.contains("UP") && !readiness.contains("jdbc"), readiness);
    }
}
