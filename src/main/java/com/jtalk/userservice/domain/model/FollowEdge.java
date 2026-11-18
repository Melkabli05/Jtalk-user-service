package com.jtalk.userservice.domain.model;

import io.micronaut.core.annotation.Introspected;

import java.time.Instant;
import java.util.UUID;

/** The other end of a follow and when it was made: what the follow graph in Redis is built from. */
@Introspected
public record FollowEdge(UUID userId, Instant createdAt) {
}
