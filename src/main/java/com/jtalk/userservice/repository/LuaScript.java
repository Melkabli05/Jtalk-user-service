package com.jtalk.userservice.repository;

import io.lettuce.core.RedisNoScriptException;
import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.api.sync.RedisCommands;
import io.micronaut.core.io.ResourceResolver;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * A Lua script loaded from the classpath with Micronaut's {@link ResourceResolver} and run by SHA-1 (a few bytes on the wire instead of
 * the whole script). If Redis does not know the script (restart, failover, {@code SCRIPT FLUSH}) the source is sent once, which also
 * caches it again.
 */
final class LuaScript {

    private final String source;
    private final String sha1;

    private LuaScript(String source) {
        this.source = source;
        try {
            this.sha1 = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(source.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    static LuaScript load(ResourceResolver resolver, String path) {
        try (var in = resolver.getResourceAsStream("classpath:" + path)
                .orElseThrow(() -> new IllegalStateException("Missing classpath resource " + path))) {
            return new LuaScript(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    <T> T execute(RedisCommands<String, String> redis, ScriptOutputType type, String[] keys, String... args) {
        try {
            return redis.evalsha(sha1, type, keys, args);
        } catch (RedisNoScriptException unknownToRedis) {
            return redis.eval(source, type, keys, args);
        }
    }
}
