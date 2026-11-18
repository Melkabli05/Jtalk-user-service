package com.jtalk.userservice.dto;

import com.jtalk.userservice.domain.entity.User;
import com.jtalk.userservice.domain.entity.UserLanguage;
import com.jtalk.userservice.dto.response.LanguageEntryResponse;
import com.jtalk.userservice.dto.response.LanguageMatrixResponse;
import com.jtalk.userservice.dto.response.UserProfileResponse;
import com.jtalk.userservice.dto.response.UserSummaryResponse;
import io.micronaut.context.annotation.Mapper;
import jakarta.inject.Singleton;

import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * Domain -> API mappings, generated at compile time by Micronaut's own {@link Mapper} (properties are matched by name, and a response
 * property with no source fails the build instead of silently going out empty).
 */
@Singleton
public interface ApiMapper {

    /** A profile with its languages. Built by hand because the languages come from another table, not from the user. */
    default UserProfileResponse toProfile(User user, List<UserLanguage> languages) {
        LanguageMatrixResponse matrix = toMatrix(user.id(), languages);
        return new UserProfileResponse(user.id(), user.username(), user.displayName(), user.bio(), user.avatarUrl(), user.countryCode(),
                user.nativeLanguage(), matrix.nativeLanguages(), matrix.learningLanguages(), user.createdAt());
    }

    @Mapper
    UserSummaryResponse toSummary(User user);

    @Mapper
    LanguageEntryResponse toEntry(UserLanguage language);

    default List<UserSummaryResponse> toSummaries(List<User> users) {
        return users.stream().map(this::toSummary).toList();
    }

    default LanguageMatrixResponse toMatrix(UUID userId, List<UserLanguage> languages) {
        return new LanguageMatrixResponse(userId,
                languages.stream().filter(UserLanguage::isNative).map(UserLanguage::languageCode).sorted().toList(),
                languages.stream().filter(language -> !language.isNative())
                        .sorted(Comparator.comparing(UserLanguage::languageCode))
                        .map(this::toEntry).toList());
    }
}
