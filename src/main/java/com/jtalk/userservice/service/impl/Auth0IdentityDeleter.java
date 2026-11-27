package com.jtalk.userservice.service.impl;

import com.jtalk.userservice.config.Auth0ManagementProperties;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.MediaType;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.exceptions.HttpClientResponseException;
import io.micronaut.http.uri.UriBuilder;
import io.micronaut.serde.annotation.Serdeable;
import jakarta.annotation.PreDestroy;
import jakarta.inject.Singleton;
import lombok.extern.slf4j.Slf4j;

import java.net.MalformedURLException;
import java.net.URI;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Deletes the caller's Auth0 user via the Management API using Micronaut's {@link HttpClient} when {@code auth0.management.*}
 * credentials are configured. Failures are logged rather than thrown so local account deletion remains idempotent.
 */
@Singleton
@Slf4j
class Auth0IdentityDeleter implements AutoCloseable {

    private static final long EXPIRY_BUFFER_SECONDS = 60;

    private final Auth0ManagementProperties properties;
    private final HttpClient http;
    private final ReentrantLock tokenLock = new ReentrantLock();
    private volatile CachedToken cachedToken;

    Auth0IdentityDeleter(Auth0ManagementProperties properties) {
        this.properties = properties;
        this.http = properties.isConfigured() ? createClient(properties) : null;
    }

    private static HttpClient createClient(Auth0ManagementProperties properties) {
        try {
            return HttpClient.create(URI.create(properties.scheme() + "://" + properties.domain()).toURL());
        } catch (MalformedURLException e) {
            throw new IllegalStateException("Invalid Auth0 management domain: " + properties.domain(), e);
        }
    }

    private Auth0IdentityDeleter(Auth0ManagementProperties properties, HttpClient http) {
        this.properties = properties;
        this.http = http;
    }

    void deleteUser(@Nullable String auth0Subject) {
        if (http == null || auth0Subject == null || auth0Subject.isBlank()) {
            return;
        }
        try {
            String token = managementToken();
            URI uri = UriBuilder.of("/api/v2/users/{id}").expand(Map.of("id", auth0Subject));
            HttpRequest<?> request = HttpRequest.DELETE(uri).bearerAuth(token);
            http.toBlocking().exchange(request);
            log.info("Deleted Auth0 identity {}", auth0Subject);
        } catch (HttpClientResponseException e) {
            if (e.getStatus() == HttpStatus.NOT_FOUND) {
                log.info("Auth0 identity {} was already absent", auth0Subject);
            } else {
                log.warn("Auth0 identity deletion for {} returned status {}", auth0Subject, e.getStatus(), e);
            }
        } catch (RuntimeException e) {
            log.warn("Could not delete Auth0 identity {}", auth0Subject, e);
        }
    }

    private String managementToken() {
        Instant now = Instant.now();
        CachedToken current = cachedToken;
        if (current != null && current.isValid(now)) {
            return current.token();
        }
        tokenLock.lock();
        try {
            now = Instant.now();
            current = cachedToken;
            if (current != null && current.isValid(now)) {
                return current.token();
            }
            TokenRequest payload = new TokenRequest(
                    properties.clientId(),
                    properties.clientSecret(),
                    "https://" + properties.domain() + "/api/v2/",
                    "client_credentials");
            TokenResponse response = http.toBlocking().retrieve(
                    HttpRequest.POST("/oauth/token", payload).contentType(MediaType.APPLICATION_JSON_TYPE),
                    TokenResponse.class);
            long ttl = Math.max(0, response.expires_in() - EXPIRY_BUFFER_SECONDS);
            cachedToken = new CachedToken(response.access_token(), now.plusSeconds(ttl));
            return response.access_token();
        } finally {
            tokenLock.unlock();
        }
    }

    @PreDestroy
    @Override
    public void close() {
        if (http != null) {
            http.close();
        }
    }

    private record CachedToken(String token, Instant expiresAt) {
        boolean isValid(Instant now) {
            return now.isBefore(expiresAt);
        }
    }

    @Serdeable.Serializable
    record TokenRequest(String client_id, String client_secret, String audience, String grant_type) {
    }

    @Serdeable.Deserializable
    record TokenResponse(String access_token, long expires_in) {
    }
}
