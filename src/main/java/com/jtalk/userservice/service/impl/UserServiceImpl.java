package com.jtalk.userservice.service.impl;

import com.jtalk.userservice.config.ProblemType;
import com.jtalk.userservice.config.UserProperties;
import com.jtalk.userservice.domain.entity.User;
import com.jtalk.userservice.domain.entity.UserLanguage;
import com.jtalk.userservice.domain.event.UserProfileUpdatedEvent;
import com.jtalk.userservice.dto.ApiMapper;
import com.jtalk.userservice.dto.request.CreateProfileRequest;
import com.jtalk.userservice.dto.request.LearningLanguageRequest;
import com.jtalk.userservice.dto.request.UpdateProfileRequest;
import com.jtalk.userservice.dto.response.UserProfileResponse;
import com.jtalk.userservice.dto.response.UserSummaryResponse;
import com.jtalk.userservice.repository.UserLanguageRepository;
import com.jtalk.userservice.repository.UserRedisRepository;
import com.jtalk.userservice.repository.UserRepository;
import com.jtalk.userservice.service.UserService;
import io.micronaut.context.event.ApplicationEventPublisher;
import io.micronaut.data.exceptions.DataAccessException;
import io.micronaut.transaction.annotation.Transactional;
import jakarta.inject.Singleton;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Profile CRUD. Reads go to the Redis cache first and fall back to PostgreSQL, which is the source of truth: a Redis failure slows a
 * read down, it never fails it. Writes go to PostgreSQL inside a transaction and publish an event; after the commit the cached copy is
 * replaced and the change announced (see {@code CacheSyncListener}), so no cache is touched by a write that rolls back.
 */
@Singleton
@Slf4j
@RequiredArgsConstructor
class UserServiceImpl implements UserService {

    /** The last code point there is: everything that starts with a prefix sorts before the prefix followed by this. */
    private static final String PAST_ANY_TEXT = Character.toString(Character.MAX_CODE_POINT);

    private final UserRepository users;
    private final UserLanguageRepository languages;
    private final UserRedisRepository cache;
    private final ApiMapper mapper;
    private final UserProperties properties;
    private final ApplicationEventPublisher<UserProfileUpdatedEvent> updated;

    @Override
    @Transactional
    public UserProfileResponse createProfile(UUID userId, CreateProfileRequest request) {
        if (users.existsById(userId)) {
            throw ProblemType.PROFILE_ALREADY_EXISTS.because("You already have a profile");
        }
        if (users.existsByUsername(request.username())) {
            throw usernameTaken(request.username());
        }
        User saved;
        try {
            saved = users.save(User.register(userId, request.username(), request.displayName(), request.bio(), request.avatarUrl(),
                    request.countryCode(), request.nativeLanguage()));
        } catch (DataAccessException e) {
            // Two requests can pass the checks above together; the database constraint decides which one wins.
            if (Violations.isUnique(e, "users_username_key")) {
                throw usernameTaken(request.username());
            }
            if (Violations.isUnique(e, "users_pkey")) {
                throw ProblemType.PROFILE_ALREADY_EXISTS.because("You already have a profile");
            }
            throw e;
        }
        languages.save(UserLanguage.nativeOf(userId, saved.nativeLanguage()));
        if (request.learningLanguages() != null) {
            setLearning(userId, saved.nativeLanguage(), request.learningLanguages());
        }
        updated.publishEvent(new UserProfileUpdatedEvent(saved));
        log.info("Created profile {} ({})", saved.id(), saved.username());
        return profileOf(saved);
    }

    @Override
    public UserProfileResponse getProfile(UUID userId) {
        return profileOf(getRequiredUser(userId));
    }

    @Override
    public User getRequiredUser(UUID userId) {
        return find(userId).orElseThrow(() -> ProblemType.USER_NOT_FOUND.because("User " + userId + " does not exist"));
    }

    @Override
    public List<User> findUsers(Collection<UUID> userIds) {
        LinkedHashSet<UUID> wanted = new LinkedHashSet<>(userIds);
        Map<UUID, User> found = new HashMap<>(cachedAll(wanted));
        List<UUID> missing = wanted.stream().filter(id -> !found.containsKey(id)).toList();
        if (!missing.isEmpty()) {
            List<User> loaded = users.findByIdIn(missing);
            loaded.forEach(user -> found.put(user.id(), user));
            cachePut(loaded);
        }
        return wanted.stream().map(found::get).filter(Objects::nonNull).toList();
    }

    @Override
    public List<UserSummaryResponse> getSummaries(Collection<UUID> userIds) {
        return mapper.toSummaries(findUsers(userIds));
    }

    @Override
    @Transactional
    public UserProfileResponse updateProfile(UUID userId, UpdateProfileRequest request) {
        // Always from the database, never the cache: the write must be based on what is really stored.
        User current = users.findById(userId)
                .orElseThrow(() -> ProblemType.USER_NOT_FOUND.because("You have no profile yet: create it first"));
        User changed = current.withProfile(request.displayName(), request.bio(), request.avatarUrl(), request.countryCode(), request.nativeLanguage());
        User saved = changed.equals(current) ? current : users.update(changed);
        // The native language first: a language being learned may be the one that is becoming native, and one that stops being the
        // primary may be in the new list of languages being learned.
        if (!saved.nativeLanguage().equals(current.nativeLanguage())) {
            makeNative(userId, saved.nativeLanguage());
        }
        if (request.learningLanguages() != null) {
            setLearning(userId, saved.nativeLanguage(), request.learningLanguages());
        }
        if (saved != current) {
            updated.publishEvent(new UserProfileUpdatedEvent(saved));
        }
        return profileOf(saved);
    }

    @Override
    public List<UserSummaryResponse> search(UUID viewerId, String query, int size) {
        String start = query.strip().toLowerCase(Locale.ROOT);
        String end = start + PAST_ANY_TEXT;
        // Handles first: someone typing "sar" means the handle sara_k before a Sara with another handle. Each query is an index range
        // that stops after one page, so the cost is the page, not the number of matches.
        Map<UUID, User> found = new LinkedHashMap<>();
        users.searchByUsernamePrefix(viewerId, start, end, size).forEach(user -> found.put(user.id(), user));
        if (found.size() < size) {
            users.searchByDisplayNamePrefix(viewerId, start, end, size).forEach(user -> found.putIfAbsent(user.id(), user));
        }
        return mapper.toSummaries(found.values().stream().limit(size).toList());
    }

    /**
     * Makes {@code wanted} the whole list of languages the user is learning: languages no longer listed are dropped, new ones added, the
     * level of the others updated. A language the user had as native becomes a learning one (they said they are learning it); the
     * primary native language cannot be. Runs inside the caller's transaction, so a request that fails (too many languages) changes nothing.
     */
    private void setLearning(UUID userId, String primaryNative, List<LearningLanguageRequest> wanted) {
        Map<String, LearningLanguageRequest> byCode = new LinkedHashMap<>();
        wanted.forEach(language -> byCode.putIfAbsent(language.languageCode(), language));
        if (byCode.containsKey(primaryNative)) {
            throw ProblemType.LANGUAGE_CONFLICT.because("'" + primaryNative + "' is your native language: you cannot also learn it");
        }
        Map<String, UserLanguage> listed = new HashMap<>();
        languages.findByUserIdOrderByLanguageCode(userId).forEach(row -> listed.put(row.languageCode(), row));
        listed.values().stream()
                .filter(row -> !row.isNative() && !byCode.containsKey(row.languageCode()))
                .forEach(row -> languages.deleteByUserIdAndLanguageCode(userId, row.languageCode()));
        byCode.forEach((code, language) -> {
            UserLanguage row = listed.get(code);
            if (row == null) {
                languages.save(UserLanguage.learning(userId, code, language.proficiencyLevel()));
            } else if (!row.equals(row.asLearning(language.proficiencyLevel()))) {
                languages.update(row.asLearning(language.proficiencyLevel()));
            }
        });
        if (languages.countByUserId(userId) > properties.maxLanguages()) {
            throw ProblemType.TOO_MANY_LANGUAGES.because("You can list at most " + properties.maxLanguages() + " languages");
        }
    }

    private UserProfileResponse profileOf(User user) {
        return mapper.toProfile(user, languages.findByUserIdOrderByLanguageCode(user.id()));
    }

    /** The primary native language always has a NATIVE row: a language the user was learning becomes native, and a new one is added. */
    private void makeNative(UUID userId, String languageCode) {
        languages.findByUserIdAndLanguageCode(userId, languageCode).ifPresentOrElse(
                row -> {
                    if (!row.isNative()) {
                        languages.update(row.asNative());
                    }
                },
                () -> languages.save(UserLanguage.nativeOf(userId, languageCode)));
    }

    private Optional<User> find(UUID userId) {
        Optional<User> cached = cachedAll(List.of(userId)).values().stream().findFirst();
        if (cached.isPresent()) {
            return cached;
        }
        Optional<User> loaded = users.findById(userId);
        loaded.ifPresent(user -> cachePut(List.of(user)));
        return loaded;
    }

    private Map<UUID, User> cachedAll(Collection<UUID> userIds) {
        try {
            return cache.findAll(userIds);
        } catch (RuntimeException e) {
            log.warn("Profile cache unavailable, reading from the database", e);
            return Map.of();
        }
    }

    private void cachePut(Collection<User> loaded) {
        try {
            cache.putAll(loaded);
        } catch (RuntimeException e) {
            log.warn("Could not cache {} profile(s)", loaded.size(), e);
        }
    }

    private static RuntimeException usernameTaken(String username) {
        return ProblemType.USERNAME_TAKEN.because("The username '" + username + "' is taken");
    }
}
