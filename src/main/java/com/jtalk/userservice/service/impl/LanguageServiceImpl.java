package com.jtalk.userservice.service.impl;

import com.jtalk.userservice.config.ProblemType;
import com.jtalk.userservice.config.UserProperties;
import com.jtalk.userservice.domain.entity.User;
import com.jtalk.userservice.domain.entity.UserLanguage;
import com.jtalk.userservice.domain.enums.LanguageType;
import com.jtalk.userservice.dto.ApiMapper;
import com.jtalk.userservice.dto.request.AddLanguageRequest;
import com.jtalk.userservice.dto.response.LanguageMatrixResponse;
import com.jtalk.userservice.dto.response.UserPageResponse;
import com.jtalk.userservice.repository.UserLanguageRepository;
import com.jtalk.userservice.repository.UserRepository;
import com.jtalk.userservice.service.LanguageService;
import com.jtalk.userservice.service.UserService;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.transaction.annotation.Transactional;
import jakarta.inject.Singleton;
import lombok.RequiredArgsConstructor;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Singleton
@RequiredArgsConstructor
class LanguageServiceImpl implements LanguageService {

    private final UserService userService;
    private final UserLanguageRepository languages;
    private final UserRepository users;
    private final ApiMapper mapper;
    private final UserProperties properties;

    @Override
    public LanguageMatrixResponse getLanguages(UUID userId) {
        userService.getRequiredUser(userId);
        return matrix(userId);
    }

    @Override
    @Transactional
    public LanguageMatrixResponse addLanguage(UUID userId, AddLanguageRequest request) {
        lockProfile(userId);
        Optional<UserLanguage> existing = languages.findByUserIdAndLanguageCode(userId, request.languageCode());
        if (existing.isPresent()) {
            UserLanguage row = existing.get();
            if (row.languageType() != request.languageType()) {
                throw ProblemType.LANGUAGE_CONFLICT.because("You already list '%s' as %s: remove it first to list it as %s"
                        .formatted(request.languageCode(), row.languageType(), request.languageType()));
            }
            if (!row.isNative()) {
                languages.update(row.withProficiency(request.proficiencyLevel()));
            }
        } else {
            if (languages.countByUserId(userId) >= properties.maxLanguages()) {
                throw ProblemType.TOO_MANY_LANGUAGES.because("You can list at most " + properties.maxLanguages() + " languages");
            }
            languages.save(request.languageType() == LanguageType.NATIVE
                    ? UserLanguage.nativeOf(userId, request.languageCode())
                    : UserLanguage.learning(userId, request.languageCode(), request.proficiencyLevel()));
        }
        return matrix(userId);
    }

    @Override
    @Transactional
    public LanguageMatrixResponse removeLanguage(UUID userId, String languageCode) {
        User user = lockProfile(userId);
        UserLanguage row = languages.findByUserIdAndLanguageCode(userId, languageCode)
                .orElseThrow(() -> ProblemType.LANGUAGE_NOT_FOUND.because("You do not list '" + languageCode + "'"));
        if (row.isNative() && row.languageCode().equals(user.nativeLanguage())) {
            throw ProblemType.PRIMARY_LANGUAGE_REQUIRED.because("'" + languageCode + "' is your primary native language: change it on your profile first");
        }
        languages.deleteByUserIdAndLanguageCode(userId, languageCode);
        return matrix(userId);
    }

    @Override
    public UserPageResponse findPartners(UUID viewerId, @Nullable String nativeLang, @Nullable String learningLang, int size,
                                         @Nullable Instant afterCreatedAt, @Nullable UUID afterId) {
        User viewer = userService.getRequiredUser(viewerId);
        // What the person speaks and what they are learning, all of it: a partner may match on any of them. Naming a language in the
        // request replaces that side with just that language.
        List<UserLanguage> mine = blankToNull(nativeLang) == null || blankToNull(learningLang) == null
                ? languages.findByUserIdOrderByLanguageCode(viewerId) : List.of();
        Set<String> speaks = new LinkedHashSet<>();
        Set<String> learns = new LinkedHashSet<>();
        if (blankToNull(nativeLang) == null) {
            speaks.add(viewer.nativeLanguage());
            mine.stream().filter(UserLanguage::isNative).map(UserLanguage::languageCode).forEach(speaks::add);
        } else {
            speaks.add(nativeLang);
        }
        if (blankToNull(learningLang) == null) {
            mine.stream().filter(language -> !language.isNative()).map(UserLanguage::languageCode).forEach(learns::add);
            if (learns.isEmpty()) {
                throw ProblemType.NO_LEARNING_LANGUAGE.because("Add a language you are learning, or say which one to match with (learningLang)");
            }
        } else {
            learns.add(learningLang);
        }

        // One extra row tells whether another page exists, without a COUNT.
        int limit = size + 1;
        List<User> rows = afterCreatedAt == null || afterId == null
                ? users.findMatches(viewerId, learns, speaks, limit)
                : users.findMatchesAfter(viewerId, learns, speaks, afterCreatedAt, afterId, limit);
        boolean hasNext = rows.size() > size;
        List<User> page = hasNext ? rows.subList(0, size) : rows;
        User last = page.isEmpty() ? null : page.getLast();
        return new UserPageResponse(mapper.toSummaries(page), hasNext,
                hasNext ? last.createdAt() : null, hasNext ? last.id() : null);
    }

    private LanguageMatrixResponse matrix(UUID userId) {
        return mapper.toMatrix(userId, languages.findByUserIdOrderByLanguageCode(userId));
    }

    private User lockProfile(UUID userId) {
        return users.findByIdForUpdate(userId)
                .orElseThrow(() -> ProblemType.USER_NOT_FOUND.because("User " + userId + " does not exist"));
    }

    @Nullable
    private static String blankToNull(@Nullable String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
