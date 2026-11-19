package com.jtalk.userservice.domain.entity;

import com.jtalk.userservice.config.UserConstants;
import io.micronaut.data.annotation.Id;
import io.micronaut.data.annotation.MappedEntity;

import java.util.UUID;

/** The picture a user uploaded: the image as it was sent (already shrunk by the browser) and the version that is part of its address. */
@MappedEntity(value = "user_avatars", schema = UserConstants.SCHEMA)
public record UserAvatar(@Id UUID userId, String contentType, byte[] image, long version) {
}
