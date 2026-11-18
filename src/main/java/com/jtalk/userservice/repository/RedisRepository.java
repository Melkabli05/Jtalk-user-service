package com.jtalk.userservice.repository;

import io.lettuce.core.LettuceFutures;
import io.lettuce.core.RedisCommandTimeoutException;
import io.lettuce.core.RedisFuture;
import io.lettuce.core.api.StatefulRedisConnection;

import java.time.Duration;
import java.util.UUID;

/**
 * What every Redis-backed repository shares: the connection, and the one definition of how a user's keys are named. The
 * {@code {userId}} hash tag puts all of a user's keys in the same cluster slot, which is what lets Lua scripts and multi-key
 * commands touch them together, so it must never be spelled by hand anywhere else.
 */
abstract class RedisRepository {

    private static final Duration TIMEOUT = Duration.ofSeconds(2);

    protected final StatefulRedisConnection<String, String> connection;

    protected RedisRepository(StatefulRedisConnection<String, String> connection) {
        this.connection = connection;
    }

    static String userKey(String kind, UUID userId) {
        return "user:" + kind + ":{" + userId + "}";
    }

    /** Waits for commands that were sent without waiting (pipelined); a command that does not answer in time fails the whole call. */
    static void await(RedisFuture<?>... futures) {
        if (!LettuceFutures.awaitAll(TIMEOUT, futures)) {
            throw new RedisCommandTimeoutException("Redis did not answer within " + TIMEOUT);
        }
    }
}
