package com.jtalk.userservice.service.impl;

import com.jtalk.userservice.config.ProblemType;
import com.jtalk.userservice.config.UserProperties;
import com.jtalk.userservice.domain.event.UserProfileUpdatedEvent;
import com.jtalk.userservice.dto.ApiMapper;
import com.jtalk.userservice.dto.response.AvatarResponse;
import com.jtalk.userservice.dto.response.UserProfileResponse;
import com.jtalk.userservice.repository.UserAvatarRepository;
import com.jtalk.userservice.repository.UserLanguageRepository;
import com.jtalk.userservice.repository.UserRepository;
import com.jtalk.userservice.service.AvatarService;
import io.micronaut.context.event.ApplicationEventPublisher;
import io.micronaut.transaction.annotation.Transactional;
import jakarta.inject.Singleton;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.Optional;
import java.util.UUID;

@Singleton
@Slf4j
@RequiredArgsConstructor
class AvatarServiceImpl implements AvatarService {

    private final UserRepository users;
    private final UserAvatarRepository avatars;
    private final UserLanguageRepository languages;
    private final ApiMapper mapper;
    private final UserProperties properties;
    private final ApplicationEventPublisher<UserProfileUpdatedEvent> updated;

    @Override
    @Transactional
    public UserProfileResponse upload(UUID userId, String contentType, byte[] image) {
        // From the database, never the cache: the write must be based on what is really stored.
        var current = users.findById(userId)
                .orElseThrow(() -> ProblemType.USER_NOT_FOUND.because("You have no profile yet: create it first"));
        AvatarImages.require(contentType, image, properties.maxAvatarBytes());
        long version = System.currentTimeMillis();
        avatars.upsert(userId, contentType, image, version);
        var saved = users.update(current.withAvatar(AvatarImages.addressOf(userId, version)));
        updated.publishEvent(new UserProfileUpdatedEvent(saved));
        log.info("Saved the picture of {} ({} bytes)", userId, image.length);
        return mapper.toProfile(saved, languages.findByUserIdOrderByLanguageCode(userId));
    }

    @Override
    public Optional<AvatarResponse> find(UUID userId) {
        return avatars.findById(userId).map(row -> new AvatarResponse(row.contentType(), row.image(), row.version()));
    }
}
