package com.jtalk.userservice.domain.event;

import java.util.Set;
import java.util.UUID;

/** Follows of these users changed in bulk (a block removes them); after the commit their cached graphs are dropped and rebuilt on demand. */
public record FollowGraphInvalidated(Set<UUID> userIds) {
}
