package com.jtalk.userservice.config;

import io.micronaut.context.annotation.ConfigurationProperties;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.core.bind.annotation.Bindable;

/**
 * Optional Auth0 Management API credentials used to delete the caller's Auth0 identity when they delete their account.
 * Bound from {@code auth0.management.*}; when {@code client-id} or {@code client-secret} is absent, Auth0 cleanup is skipped.
 */
@ConfigurationProperties("auth0.management")
public record Auth0ManagementProperties(
        @Nullable String domain,
        @Nullable String clientId,
        @Nullable String clientSecret,
        @Bindable(defaultValue = "https") String scheme) {

    public boolean isConfigured() {
        return domain != null && !domain.isBlank()
                && clientId != null && !clientId.isBlank()
                && clientSecret != null && !clientSecret.isBlank();
    }
}
