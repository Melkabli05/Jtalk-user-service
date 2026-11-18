package com.jtalk.userservice.controller;

import io.micronaut.core.convert.ArgumentConversionContext;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.bind.binders.AnnotatedRequestArgumentBinder;
import jakarta.inject.Singleton;

import java.util.Optional;
import java.util.UUID;

/**
 * Resolves {@link CurrentUser}: the caller is the name Micronaut Security took from the access token's user-id claim, which
 * {@code UserIdClaimValidator} guarantees is a UUID. Controllers all need it as a {@code UUID}, so it is converted here once.
 */
@Singleton
class CurrentUserBinder implements AnnotatedRequestArgumentBinder<CurrentUser, UUID> {

    @Override
    public Class<CurrentUser> getAnnotationType() {
        return CurrentUser.class;
    }

    @Override
    public BindingResult<UUID> bind(ArgumentConversionContext<UUID> context, HttpRequest<?> source) {
        Optional<UUID> userId = source.getUserPrincipal().map(principal -> UUID.fromString(principal.getName()));
        return () -> userId;
    }
}
