package com.jtalk.userservice;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;
import com.jtalk.userservice.support.ApiTestBase;
import com.jtalk.userservice.support.RedisCommandRecorder;
import com.jtalk.userservice.support.RedisCommandRecorder.Trace;
import io.micronaut.http.HttpRequest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the optimizations on the hot paths by counting what a request really asks Redis, so a regression (a lookup per user, a check
 * that waits for the one before it) fails a test instead of showing up as latency under load. Round trips are what cost time over a
 * network; commands sent together share one.
 */
class PerformanceBudgetTest extends ApiTestBase {

    @Inject
    RedisCommandRecorder redisCommands;

    private List<String> names(int count) {
        return IntStream.range(0, count).mapToObj(i -> "user" + i).toList();
    }

    private Trace batch(List<String> names) {
        List<UUID> ids = names.stream().map(ApiTestBase::id).toList();
        return redisCommands.during(() -> http.retrieve(
                HttpRequest.POST("/api/v1/users/batch", Map.of("ids", ids)).bearerAuth(token("user0")), MAP_LIST));
    }

    @Test
    void aWholeRoomIsOneRoundTripWhenWarm_andTwoPlusItsWritesWhenCold() {
        List<String> room = names(50);
        room.forEach(this::register);

        Trace warm = batch(room);
        assertEquals(1, warm.roundTrips(), "all 50 profiles are read together");
        assertEquals(50, warm.count("HGETALL"));
        assertEquals(50, warm.commands().size(), "nothing but the reads");

        redis.sync().flushall();
        Trace cold = batch(room);
        assertEquals(50, cold.count("HGETALL"));
        assertEquals(50, cold.count("EVALSHA"), "each missing profile is stored by digest, not by sending the script 50 times");
        assertEquals(0, cold.count("EVAL"));
        assertTrue(cold.roundTrips() <= 3, "read, load the script once, store everything together: " + cold);
        assertEquals(1, batch(room).roundTrips(), "and the next room view is warm again");
    }

    @Test
    void storingProfilesSurvivesRedisForgettingItsScripts() {
        List<String> room = names(10);
        room.forEach(this::register);
        redis.sync().flushall();
        batch(room);                       // loads the script and stores everyone
        redis.sync().flushall();
        redis.sync().scriptFlush();        // as after a Redis restart or failover

        batch(room);

        assertEquals("user3", redis.sync().hget("user:profile:{" + id("user3") + "}", "displayName"), "stored the slow way instead of failing");
        assertEquals(1, batch(room).roundTrips());
    }

    @Test
    void aFollowRepeatedIsOneRoundTripAndNeverTouchesTheDatabaseOrTheProfiles() {
        register("alice");
        register("bob");
        follow("alice", "bob");

        Trace repeat = redisCommands.during(() -> follow("alice", "bob"));

        assertEquals(List.of("ZCARD", "ZSCORE"), repeat.commands().stream().sorted().toList(), "where alice stands, and nothing else");
        assertEquals(1, repeat.roundTrips());
    }

    @Test
    void aProfilePageIsOneRoundTripWhenItsSetsAreCached() {
        register("alice");
        register("bob");
        follow("alice", "bob");
        social("alice", "bob");   // loads bob's sets

        Trace stats = redisCommands.during(() -> social("alice", "bob"));

        assertEquals(1, stats.roundTrips(), "both counts and both relationship flags are read together");
        assertEquals(List.of("ZCARD", "ZCARD", "ZSCORE", "ZSCORE"), stats.commands().stream().sorted().toList());
    }

    @Test
    void aFollowerListIsTwoRoundTripsWhateverItsSize() {
        register("star");
        for (int i = 0; i < 12; i++) {
            register("fan" + i);
            follow("fan" + i, "star");
        }
        get(userPath("star", "/followers?size=10"), "fan0");   // warm

        Trace page = redisCommands.during(() -> get(userPath("star", "/followers?size=10"), "fan0"));

        assertEquals(2, page.roundTrips(), "the set's page and size together, then the profiles of the page together");
        assertEquals(1, page.count("ZREVRANGE"));
        assertEquals(10, page.count("HGETALL"), "one per person on the page, pipelined");
    }

    @Test
    void aNewFollowIsThreeRoundTripsWhenTheSetsAreCached() {
        register("alice");
        register("bob");
        social("alice", "alice");   // alice's sets are cached
        social("alice", "bob");     // and bob's

        Trace trace = redisCommands.during(() -> follow("alice", "bob"));

        assertEquals(3, trace.roundTrips(), "where alice stands, then both profiles, then both sets updated together: " + trace);
        assertEquals(2, trace.count("EVAL"), "both sets are updated");
        assertEquals(2, trace.count("HGETALL"), "both profiles in one read");
        assertEquals(List.of("ZSCORE", "ZCARD"), trace.commands().subList(0, 2).stream().sorted(java.util.Comparator.reverseOrder()).toList());
    }

    @Test
    void typeaheadUsesOneSqlStatementEvenWhenItNeedsDisplayNameMatches() {
        register("viewer");
        register("sam");
        register("zed");
        patch("/api/v1/users/me", "zed", Map.of("displayName", "Sarah"));

        var queries = (Logger) LoggerFactory.getLogger("io.micronaut.data.query");
        Level previousLevel = queries.getLevel();
        var statements = new AtomicInteger();
        var counter = new AppenderBase<ILoggingEvent>() {
            @Override
            protected void append(ILoggingEvent event) {
                statements.incrementAndGet();
            }
        };
        counter.setContext(queries.getLoggerContext());
        counter.start();
        queries.addAppender(counter);
        queries.setLevel(Level.DEBUG);
        try {
            var matches = getList("/api/v1/users/search?q=sa&size=3", "viewer");
            assertEquals(List.of("sam", "zed"), matches.stream().map(user -> user.get("username")).toList());
            assertEquals(1, statements.get());
        } finally {
            queries.setLevel(previousLevel);
            queries.detachAppender(counter);
            counter.stop();
        }
    }
}
