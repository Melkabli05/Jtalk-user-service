package com.jtalk.userservice.controller;

import io.micronaut.context.annotation.Value;
import io.micronaut.core.convert.ArgumentConversionContext;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.bind.binders.AnnotatedRequestArgumentBinder;
import jakarta.inject.Singleton;

import java.util.Optional;
import java.util.UUID;

/**
 * Resolves {@link CurrentUser}. With security on, the caller is the token's user-id claim (Auth0's custom claim), which
 * {@code UserIdClaimValidator} guarantees is a UUID. With security off (local work only) there is no token, so the caller is
 * named by the {@value #USER_HEADER} header instead, as in the live communication service; a missing or malformed one is a 400.
 */
@Singleton
class CurrentUserBinder implements AnnotatedRequestArgumentBinder<CurrentUser, UUID> {

    static final String USER_HEADER = "X-User-Id";

    private final boolean securityEnabled;

    CurrentUserBinder(@Value("${micronaut.security.enabled:true}") boolean securityEnabled) {
        this.securityEnabled = securityEnabled;
    }

    @Override
    public Class<CurrentUser> getAnnotationType() {
        return CurrentUser.class;
    }

    @Override
    public BindingResult<UUID> bind(ArgumentConversionContext<UUID> context, HttpRequest<?> source) {
        Optional<UUID> userId = securityEnabled
                ? source.getUserPrincipal().map(principal -> UUID.fromString(principal.getName()))
                : source.getHeaders().get(USER_HEADER, UUID.class);
        return () -> userId;
    }
}
