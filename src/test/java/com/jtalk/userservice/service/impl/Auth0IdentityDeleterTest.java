package com.jtalk.userservice.service.impl;

import com.jtalk.userservice.config.Auth0ManagementProperties;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

class Auth0IdentityDeleterTest {

    @Test
    void skipsWhenUnconfiguredOrSubjectBlank() {
        try (Auth0IdentityDeleter deleter = new Auth0IdentityDeleter(
                new Auth0ManagementProperties("example.auth0.com", "", "", "https"))) {
            assertDoesNotThrow(() -> deleter.deleteUser("auth0|123"));
            assertDoesNotThrow(() -> deleter.deleteUser(null));
        }
    }

    @Test
    void exchangesTokenOnceAndDeletesEncodedAuth0User() throws IOException {
        AtomicInteger tokenCalls = new AtomicInteger();
        List<String> deletePaths = new CopyOnWriteArrayList<>();

        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/oauth/token", exchange -> {
            tokenCalls.incrementAndGet();
            byte[] resp = "{\"access_token\":\"mgmt-tok\",\"expires_in\":3600}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, resp.length);
            exchange.getResponseBody().write(resp);
            exchange.close();
        });
        server.createContext("/api/v2/users/", exchange -> {
            deletePaths.add(exchange.getRequestMethod() + " " + exchange.getRequestURI().getRawPath());
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
        });
        server.start();
        try {
            String domain = "127.0.0.1:" + server.getAddress().getPort();
            try (Auth0IdentityDeleter deleter = new Auth0IdentityDeleter(
                    new Auth0ManagementProperties(domain, "cid", "csec", "http"))) {
                deleter.deleteUser("auth0|user-1");
                deleter.deleteUser("google-oauth2|user-2");
            }

            assertEquals(1, tokenCalls.get(), "management token should be cached");
            assertEquals(List.of(
                    "DELETE /api/v2/users/auth0%7Cuser-1",
                    "DELETE /api/v2/users/google-oauth2%7Cuser-2"), deletePaths);
        } finally {
            server.stop(0);
        }
    }
}
