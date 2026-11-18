package com.jtalk.userservice.service.impl;

import com.jtalk.userservice.config.ProblemType;
import com.jtalk.userservice.domain.entity.User;
import com.jtalk.userservice.domain.entity.UserLanguage;
import com.jtalk.userservice.domain.event.UserProfileUpdatedEvent;
import com.jtalk.userservice.dto.ApiMapper;
import com.jtalk.userservice.dto.request.CreateProfileRequest;
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

    private static final String LIKE_ESCAPE = "!";

    private final UserRepository users;
    private final UserLanguageRepository languages;
    private final UserRedisRepository cache;
    private final ApiMapper mapper;
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
        updated.publishEvent(new UserProfileUpdatedEvent(saved));
        log.info("Created profile {} ({})", saved.id(), saved.username());
        return mapper.toProfile(saved);
    }

    @Override
    public UserProfileResponse getProfile(UUID userId) {
        return mapper.toProfile(getRequiredUser(userId));
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
        if (changed.equals(current)) {
            return mapper.toProfile(current);
        }
        User saved = users.update(changed);
        if (!saved.nativeLanguage().equals(current.nativeLanguage())) {
            makeNative(userId, saved.nativeLanguage());
        }
        updated.publishEvent(new UserProfileUpdatedEvent(saved));
        return mapper.toProfile(saved);
    }

    @Override
    public List<UserSummaryResponse> search(UUID viewerId, String query, int size) {
        String prefix = escapeLike(query.strip().toLowerCase(Locale.ROOT)) + "%";
        return mapper.toSummaries(users.searchByPrefix(viewerId, prefix, size));
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

    private static String escapeLike(String text) {
        return text.replace(LIKE_ESCAPE, LIKE_ESCAPE + LIKE_ESCAPE).replace("%", LIKE_ESCAPE + "%").replace("_", LIKE_ESCAPE + "_");
    }

    private static RuntimeException usernameTaken(String username) {
        return ProblemType.USERNAME_TAKEN.because("The username '" + username + "' is taken");
    }
}
