package com.jtalk.userservice.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.micronaut.serde.annotation.Serdeable;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * One keyset-paginated page of users. When {@code hasNext}, ask for the next page with {@code nextAfterCreatedAt} and {@code nextAfterId}
 * (they are the last user's position); both are null on the last page.
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
@Serdeable.Serializable
public record UserPageResponse(
        List<DiscoveredUserResponse> content,
        boolean hasNext,
        Instant nextAfterCreatedAt,
        UUID nextAfterId) {
}
