package com.jtalk.userservice.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.micronaut.serde.annotation.Serdeable;

import java.util.List;
import java.util.UUID;

/** What a user speaks and what they are learning, each sorted by language code. Empty lists stay in the payload. */
@JsonInclude(JsonInclude.Include.ALWAYS)
@Serdeable.Serializable
public record LanguageMatrixResponse(
        UUID userId,
        List<String> nativeLanguages,
        List<LanguageEntryResponse> learningLanguages) {
}
