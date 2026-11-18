package com.jtalk.userservice.domain.entity;

import com.jtalk.userservice.config.UserConstants;
import com.jtalk.userservice.domain.enums.LanguageType;
import com.jtalk.userservice.domain.enums.ProficiencyLevel;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.data.annotation.AutoPopulated;
import io.micronaut.data.annotation.Id;
import io.micronaut.data.annotation.MappedEntity;

import java.util.UUID;

/** One row of a user's language matrix. A user lists a language once: either as native or as one they are learning. */
@MappedEntity(value = "user_languages", schema = UserConstants.SCHEMA)
public record UserLanguage(
        @Id @AutoPopulated UUID id,
        UUID userId,
        String languageCode,
        LanguageType languageType,
        @Nullable ProficiencyLevel proficiencyLevel) {

    public static UserLanguage nativeOf(UUID userId, String languageCode) {
        return new UserLanguage(null, userId, languageCode, LanguageType.NATIVE, null);
    }

    /** A language being learned; with no level stated the user is a beginner. */
    public static UserLanguage learning(UUID userId, String languageCode, @Nullable ProficiencyLevel level) {
        return new UserLanguage(null, userId, languageCode, LanguageType.LEARNING, level == null ? ProficiencyLevel.BEGINNER : level);
    }

    public UserLanguage withProficiency(@Nullable ProficiencyLevel level) {
        return new UserLanguage(id, userId, languageCode, languageType, level == null ? ProficiencyLevel.BEGINNER : level);
    }

    /** The same language, now one the person is learning (a native language they have decided to study, or a change of level). */
    public UserLanguage asLearning(@Nullable ProficiencyLevel level) {
        return new UserLanguage(id, userId, languageCode, LanguageType.LEARNING, level == null ? ProficiencyLevel.BEGINNER : level);
    }

    public UserLanguage asNative() {
        return new UserLanguage(id, userId, languageCode, LanguageType.NATIVE, null);
    }

    public boolean isNative() {
        return languageType == LanguageType.NATIVE;
    }
}
