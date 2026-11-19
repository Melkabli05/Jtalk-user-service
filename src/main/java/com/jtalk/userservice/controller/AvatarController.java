package com.jtalk.userservice.controller;

import com.jtalk.userservice.config.ProblemType;
import com.jtalk.userservice.dto.response.AvatarResponse;
import com.jtalk.userservice.dto.response.UserProfileResponse;
import com.jtalk.userservice.service.AvatarService;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.MediaType;
import io.micronaut.http.annotation.Body;
import io.micronaut.http.annotation.Consumes;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.Header;
import io.micronaut.http.annotation.Put;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.scheduling.annotation.ExecuteOn;
import io.micronaut.security.annotation.Secured;
import io.micronaut.security.rules.SecurityRule;
import lombok.RequiredArgsConstructor;

import java.util.UUID;

/** Uploaded profile pictures: the caller sets their own, and anyone may fetch one (an {@code <img>} cannot send a token). */
@Controller("/api/v1/users")
@Secured(SecurityRule.IS_AUTHENTICATED)
@ExecuteOn(TaskExecutors.VIRTUAL)
@RequiredArgsConstructor
class AvatarController {

    private final AvatarService avatars;

    /** The picture as the body, with its type as the content type (the service accepts JPEG and PNG). The browser has already shrunk it. */
    @Put("/me/avatar")
    @Consumes(MediaType.ALL)
    UserProfileResponse upload(@CurrentUser UUID userId, @Header("Content-Type") String contentType, @Body byte[] image) {
        return avatars.upload(userId, contentType, image);
    }

    /**
     * Public: pictures are shown in rooms and lists to people who are signed in, but the browser fetches them without a token. The version
     * in the address makes a copy valid for ever; an address with an old version still answers with the current picture, for a short time.
     */
    @Get("/{userId}/avatar/{version}")
    @Secured(SecurityRule.IS_ANONYMOUS)
    HttpResponse<byte[]> get(UUID userId, long version) {
        AvatarResponse avatar = avatars.find(userId)
                .orElseThrow(() -> ProblemType.USER_NOT_FOUND.because("This user has no uploaded picture"));
        String caching = avatar.version() == version ? "public, max-age=31536000, immutable" : "public, max-age=300";
        return HttpResponse.ok(avatar.image()).contentType(avatar.contentType()).header("Cache-Control", caching);
    }
}
