package com.jtalk.userservice.support;

import io.lettuce.core.RedisClient;
import io.lettuce.core.event.command.CommandFailedEvent;
import io.lettuce.core.event.command.CommandListener;
import io.lettuce.core.event.command.CommandStartedEvent;
import io.lettuce.core.event.command.CommandSucceededEvent;
import io.micronaut.context.event.BeanCreatedEvent;
import io.micronaut.context.event.BeanCreatedEventListener;
import jakarta.inject.Singleton;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * Records the Redis commands the application issues, and how many round trips they cost. A command sent while none is waiting for its
 * answer starts a new round trip; commands sent together (pipelined) share one. Lettuce only attaches a command listener to connections
 * created after it is registered, so it is installed here, when Micronaut creates the client and before any connection exists.
 */
@Singleton
public class RedisCommandRecorder implements BeanCreatedEventListener<RedisClient> {

    /** What one request cost in Redis. */
    public record Trace(List<String> commands, int roundTrips) {

        public long count(String command) {
            return commands.stream().filter(command::equals).count();
        }
    }

    private final List<String> commands = new CopyOnWriteArrayList<>();
    private final AtomicInteger inFlight = new AtomicInteger();
    private final AtomicInteger roundTrips = new AtomicInteger();
    private volatile boolean recording;

    @Override
    public RedisClient onCreated(BeanCreatedEvent<RedisClient> event) {
        RedisClient client = event.getBean();
        client.addListener(new CommandListener() {
            @Override
            public void commandStarted(CommandStartedEvent started) {
                if (recording) {
                    if (inFlight.getAndIncrement() == 0) {
                        roundTrips.incrementAndGet();
                    }
                    commands.add(started.getCommand().getType().toString());
                }
            }

            @Override
            public void commandSucceeded(CommandSucceededEvent succeeded) {
                finished();
            }

            @Override
            public void commandFailed(CommandFailedEvent failed) {
                finished();
            }

            private void finished() {
                if (recording) {
                    inFlight.decrementAndGet();
                }
            }
        });
        return client;
    }

    /** The Redis commands and round trips while {@code request} runs (one request at a time: tests run sequentially). */
    public Trace during(Supplier<?> request) {
        commands.clear();
        inFlight.set(0);
        roundTrips.set(0);
        recording = true;
        try {
            request.get();
        } finally {
            recording = false;
        }
        return new Trace(List.copyOf(commands), roundTrips.get());
    }
}
