package com.jtalk.userservice.dto.response;

/** An uploaded picture, ready to be sent: its bytes, their type and the version that is part of its address. */
public record AvatarResponse(String contentType, byte[] image, long version) {
}
