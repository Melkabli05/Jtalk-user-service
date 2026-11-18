package com.jtalk.userservice.support;

import com.auth0.jwt.JWT;
import com.auth0.jwt.JWTCreator;
import com.auth0.jwt.algorithms.Algorithm;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.util.Base64;
import java.util.Date;
import java.util.Map;
import java.util.UUID;

/**
 * A stand-in for the Auth0 tenant: an RSA key pair, a JWKS endpoint that publishes its public half, and access tokens shaped like Auth0's
 * (RS256, {@code kid}, issuer, audience, an {@code auth0|...} subject that is not a UUID, and the custom user-id claim an Auth0 Action adds).
 * The service validates them through its real configuration, so the tests exercise the same path as production, signature check included.
 */
public final class FakeAuth0 {

    public static final String ISSUER = "https://jtalk-test.eu.auth0.com/";
    public static final String AUDIENCE = "https://api.jtalk.test";
    public static final String USER_ID_CLAIM = "https://jtalk.app/user_id";
    private static final String KEY_ID = "test-key";

    private static final KeyPair KEYS = newKeyPair();
    private static final String JWKS_URL = startJwksServer();

    private FakeAuth0() {
    }

    /** The properties that point the service at this fake tenant instead of the real one. */
    public static Map<String, String> properties() {
        return Map.of(
                "micronaut.security.token.jwt.signatures.jwks.auth0.url", JWKS_URL,
                "micronaut.security.token.jwt.claims-validators.issuer", ISSUER,
                "micronaut.security.token.jwt.claims-validators.audience", AUDIENCE,
                "micronaut.security.token.name-key", USER_ID_CLAIM);
    }

    /** A valid access token for this user. */
    public static String tokenFor(UUID userId) {
        return sign(baseToken("auth0|" + userId).withClaim(USER_ID_CLAIM, userId.toString()), KEYS);
    }

    /** Everything a good token has except the user-id claim, so a test can leave out or change one thing. */
    public static JWTCreator.Builder baseToken(String subject) {
        return JWT.create()
                .withKeyId(KEY_ID)
                .withIssuer(ISSUER)
                .withAudience(AUDIENCE)
                .withSubject(subject)
                .withExpiresAt(new Date(System.currentTimeMillis() + 3_600_000));
    }

    public static String sign(JWTCreator.Builder token) {
        return sign(token, KEYS);
    }

    /** Signs with a key the service has never been told about, as a forger would. */
    public static String signWithAnotherKey(JWTCreator.Builder token) {
        return sign(token, newKeyPair());
    }

    private static String sign(JWTCreator.Builder token, KeyPair keys) {
        return token.sign(Algorithm.RSA256((RSAPublicKey) keys.getPublic(), (RSAPrivateKey) keys.getPrivate()));
    }

    private static KeyPair newKeyPair() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return generator.generateKeyPair();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String startJwksServer() {
        RSAPublicKey key = (RSAPublicKey) KEYS.getPublic();
        String jwks = """
                {"keys":[{"kty":"RSA","use":"sig","alg":"RS256","kid":"%s","n":"%s","e":"%s"}]}"""
                .formatted(KEY_ID, base64Url(key.getModulus()), base64Url(key.getPublicExponent()));
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/.well-known/jwks.json", exchange -> {
                byte[] body = jwks.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
                exchange.close();
            });
            server.start();   // its threads are daemons: it ends with the test JVM
            return "http://127.0.0.1:" + server.getAddress().getPort() + "/.well-known/jwks.json";
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String base64Url(BigInteger number) {
        byte[] bytes = number.toByteArray();
        int start = bytes[0] == 0 ? 1 : 0;   // a positive number's leading sign byte is not part of the key
        return Base64.getUrlEncoder().withoutPadding().encodeToString(java.util.Arrays.copyOfRange(bytes, start, bytes.length));
    }
}
