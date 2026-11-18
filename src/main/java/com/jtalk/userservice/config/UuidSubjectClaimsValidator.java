package com.jtalk.userservice.config;

import io.micronaut.security.token.Claims;
import io.micronaut.security.token.jwt.validator.GenericJwtClaimsValidator;
import jakarta.inject.Singleton;

import java.util.UUID;

/**
 * Every API call identifies the user by the token's subject, which must be their UUID (the same id the live communication service
 * uses). A token without one is rejected with 401 here, by Micronaut Security, so no controller ever has to cope with a malformed id.
 */
@Singleton
class UuidSubjectClaimsValidator<T> implements GenericJwtClaimsValidator<T> {

    @Override
    public boolean validate(Claims claims, T request) {
        try {
            UUID.fromString(String.valueOf(claims.get(Claims.SUBJECT)));
            return true;
        } catch (IllegalArgumentException _) {
            return false;
        }
    }
}
