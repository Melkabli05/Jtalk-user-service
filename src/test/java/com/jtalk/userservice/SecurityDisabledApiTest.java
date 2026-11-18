package com.jtalk.userservice;

import com.jtalk.userservice.support.ApiTestBase;
import io.micronaut.context.annotation.Property;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.client.exceptions.HttpClientResponseException;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** With security switched off (micronaut.security.enabled=false): no token is needed, and the caller is named by the X-User-Id header. */
@Property(name = "micronaut.security.enabled", value = "false")
class SecurityDisabledApiTest extends ApiTestBase {

    private static final Map<String, Object> BODY = Map.of("username", "open", "displayName", "Open", "countryCode", "MA", "nativeLanguage", "ar");

    @Test
    void callsWorkWithoutATokenAndTheHeaderNamesTheUser() {
        HttpResponse<Map<String, Object>> created = http.exchange(
                HttpRequest.POST("/api/v1/users/me", BODY).header("X-User-Id", id("open").toString()), MAP);

        assertEquals(HttpStatus.CREATED, created.status());
        assertEquals(id("open").toString(), created.body().get("id"), "the header user got the profile");
    }

    @Test
    void aCallWithoutAUserIdIsABadRequestNotAServerError() {
        var request = HttpRequest.POST("/api/v1/users/me", BODY);
        assertEquals(HttpStatus.BAD_REQUEST, assertThrows(HttpClientResponseException.class, () -> http.exchange(request, MAP)).getStatus());

        var malformed = HttpRequest.POST("/api/v1/users/me", BODY).header("X-User-Id", "not-a-uuid");
        assertEquals(HttpStatus.BAD_REQUEST, assertThrows(HttpClientResponseException.class, () -> http.exchange(malformed, MAP)).getStatus());
    }
}
