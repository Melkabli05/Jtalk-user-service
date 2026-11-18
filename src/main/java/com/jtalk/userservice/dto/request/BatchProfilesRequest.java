package com.jtalk.userservice.dto.request;

import com.jtalk.userservice.config.UserConstants;
import io.micronaut.serde.annotation.Serdeable;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

/** The users to resolve in one call: the speakers and listeners of a room. A body, not a query string, because 500 ids do not fit in a URL. */
@Serdeable.Deserializable
public record BatchProfilesRequest(
        @NotEmpty @Size(max = UserConstants.MAX_BATCH_IDS) List<@NotNull UUID> ids) {
}
