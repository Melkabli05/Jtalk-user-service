package com.jtalk.userservice.dto.response;

import io.micronaut.serde.annotation.Serdeable;

/** Whether a candidate username is available to the caller. */
@Serdeable.Serializable
public record UsernameAvailabilityResponse(String username, boolean available) {
}
