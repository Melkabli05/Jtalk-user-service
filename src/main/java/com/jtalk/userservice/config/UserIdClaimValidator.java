package com.jtalk.userservice.config;

import io.micronaut.security.token.Claims;
import io.micronaut.security.token.config.TokenConfiguration;
import io.micronaut.security.token.jwt.validator.GenericJwtClaimsValidator;
import jakarta.inject.Singleton;
import lombok.RequiredArgsConstructor;

import java.util.UUID;

/**
 * Every API call identifies the user by one claim of the access token ({@code micronaut.security.token.name-key}: Auth0's custom user-id
 * claim), which must be the user's UUID, the same id the live communication service uses. A token without it is rejected with 401 here,
 * by Micronaut Security, so no controller ever has to cope with a missing or malformed user id.
 */
@Singleton
@RequiredArgsConstructor
class UserIdClaimValidator<T> implements GenericJwtClaimsValidator<T> {

    private final TokenConfiguration tokens;

    @Override
    public boolean validate(Claims claims, T request) {
        Object userId = claims.get(tokens.getNameKey());
        if (userId == null) {
            return false;
        }
        try {
            UUID.fromString(userId.toString());
            return true;
        } catch (IllegalArgumentException _) {
            return false;
        }
    }
}
