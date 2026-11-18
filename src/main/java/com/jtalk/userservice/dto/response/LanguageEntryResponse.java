package com.jtalk.userservice.dto.response;

import com.jtalk.userservice.domain.enums.ProficiencyLevel;
import io.micronaut.serde.annotation.Serdeable;

/** A language being learned and how far along the learner is. */
@Serdeable.Serializable
public record LanguageEntryResponse(String languageCode, ProficiencyLevel proficiencyLevel) {
}
