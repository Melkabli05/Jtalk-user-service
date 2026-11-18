package com.jtalk.userservice.support;

import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import com.jtalk.userservice.repository.UserRepository;
import io.lettuce.core.api.StatefulRedisConnection;
import io.micronaut.core.type.Argument;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.MutableHttpRequest;
import io.micronaut.http.client.BlockingHttpClient;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.annotation.Client;
import io.micronaut.http.client.exceptions.HttpClientResponseException;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;

import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Shared plumbing for end-to-end tests: real PostgreSQL and Redis (Test Resources). Users are written as names ("alice"); {@link #id(String)}
 * turns each into a stable UUID, which is what the API works with, and {@link #token(String)} signs the bearer token that names them.
 */
// Not transactional: a test method would otherwise run in a transaction the HTTP server (another connection) can never see into.
@MicronautTest(transactional = false)
public abstract class ApiTestBase {

    private static final String JWT_SECRET = "test-jwt-secret-test-jwt-secret-0123456789";

    protected static final Argument<Map<String, Object>> MAP = Argument.mapOf(String.class, Object.class);
    protected static final Argument<List<Map<String, Object>>> MAP_LIST = Argument.listOf(MAP);

    @Inject
    @Client("/")
    HttpClient httpClient;

    @Inject
    protected UserRepository userRepository;

    @Inject
    protected StatefulRedisConnection<String, String> redis;

    protected BlockingHttpClient http;

    @BeforeEach
    void setUpClient() {
        http = httpClient.toBlocking();
        userRepository.deleteAll();   // the other tables follow through ON DELETE CASCADE
        redis.sync().flushall();      // so no cached profile or graph outlives the rows it was built from
    }

    // ---- identities -------------------------------------------------------------------------------

    public static UUID id(String name) {
        return UUID.nameUUIDFromBytes(name.getBytes());
    }

    protected static String username(String name) {
        return name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_.]", "_");
    }

    public static String token(String userName) {
        return tokenWithSubject(id(userName).toString(), JWT_SECRET);
    }

    /** A correctly signed token whose subject is whatever the test needs, e.g. one that is not a UUID. */
    public static String tokenWithSubject(String subject, String secret) {
        return JWT.create()
                .withSubject(subject)
                .withExpiresAt(new Date(System.currentTimeMillis() + 3_600_000))
                .sign(Algorithm.HMAC256(secret));
    }

    // ---- requests ---------------------------------------------------------------------------------

    protected Map<String, Object> register(String name) {
        return register(name, "en");
    }

    protected Map<String, Object> register(String name, String nativeLanguage) {
        return http.retrieve(HttpRequest.POST("/api/v1/users/me", Map.of(
                "username", username(name), "displayName", name, "countryCode", "MA", "nativeLanguage", nativeLanguage))
                .bearerAuth(token(name)), MAP);
    }

    protected Map<String, Object> get(String path, String asUser) {
        return http.retrieve(HttpRequest.GET(path).bearerAuth(token(asUser)), MAP);
    }

    protected List<Map<String, Object>> getList(String path, String asUser) {
        return http.retrieve(HttpRequest.GET(path).bearerAuth(token(asUser)), MAP_LIST);
    }

    protected HttpResponse<?> send(MutableHttpRequest<?> request, String asUser) {
        return http.exchange(request.bearerAuth(token(asUser)));
    }

    protected Map<String, Object> post(String path, String asUser, Object body) {
        return http.retrieve(HttpRequest.POST(path, body).bearerAuth(token(asUser)), MAP);
    }

    protected Map<String, Object> patch(String path, String asUser, Object body) {
        return http.retrieve(HttpRequest.PATCH(path, body).bearerAuth(token(asUser)), MAP);
    }

    protected static String userPath(String name, String suffix) {
        return "/api/v1/users/" + id(name) + suffix;
    }

    protected HttpResponse<?> follow(String who, String target) {
        return send(HttpRequest.POST(userPath(target, "/follow"), ""), who);
    }

    protected HttpResponse<?> unfollow(String who, String target) {
        return send(HttpRequest.DELETE(userPath(target, "/follow")), who);
    }

    protected HttpResponse<?> block(String who, String target) {
        return send(HttpRequest.POST(userPath(target, "/block"), ""), who);
    }

    protected Map<String, Object> social(String viewer, String target) {
        return get(userPath(target, "/social"), viewer);
    }

    /** The ids on one page of a follower or following list, in the order they came. */
    @SuppressWarnings("unchecked")
    protected List<String> listedIds(Map<String, Object> page) {
        return ((List<Map<String, Object>>) page.get("content")).stream().map(user -> (String) user.get("id")).toList();
    }

    protected static List<String> ids(String... names) {
        return java.util.Arrays.stream(names).map(name -> id(name).toString()).toList();
    }

    // ---- assertions -------------------------------------------------------------------------------

    /** @param code expected problem code, or null to only check status and content type */
    protected Map<String, Object> assertProblem(HttpStatus status, String code, Runnable call) {
        var ex = assertThrows(HttpClientResponseException.class, call::run);
        assertEquals(status, ex.getStatus());
        assertEquals("application/problem+json", ex.getResponse().getContentType().orElseThrow().toString());
        Map<String, Object> problem = ex.getResponse().getBody(MAP).orElseThrow();
        if (code != null) {
            assertEquals("urn:jtalk:user:problem:" + code, problem.get("type"), problem.toString());
        }
        assertEquals(status.getCode(), problem.get("status"));
        return problem;
    }

    /** Polls until the condition holds, failing after {@code timeoutMillis}. */
    protected static void await(String what, long timeoutMillis, BooleanSupplier condition) {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (!condition.getAsBoolean()) {
            if (System.currentTimeMillis() > deadline) {
                throw new AssertionError("Timed out waiting for: " + what);
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError("Interrupted while waiting for: " + what, e);
            }
        }
    }
}
