package com.jtalk.userservice.config;

import io.micronaut.context.annotation.ConfigurationProperties;
import io.micronaut.core.bind.annotation.Bindable;

import java.time.Duration;

/** Tunables, bound from the {@code user.*} properties. */
@ConfigurationProperties("user")
public record UserProperties(
        /* How long a cached profile lives. A profile change replaces the cached copy at once, so this only bounds how long a copy nobody changed stays. */
        @Bindable(defaultValue = "PT24H") Duration profileCacheTtl,
        /* How long a follow graph kept in Redis lives before it is rebuilt from PostgreSQL. */
        @Bindable(defaultValue = "PT6H") Duration graphCacheTtl,
        /* Following more accounts than this is refused, which also bounds what one user's "following" set can cost in Redis. */
        @Bindable(defaultValue = "5000") int maxFollowing,
        /* Native and learning languages a user may list in total. */
        @Bindable(defaultValue = "20") int maxLanguages,
        /* How long a user must wait after changing their username before changing it again. */
        @Bindable(defaultValue = "P30D") Duration usernameChangeInterval,
        /* The largest uploaded picture, in bytes. The browser shrinks it to a small square first, so this is generous. */
        @Bindable(defaultValue = "204800") int maxAvatarBytes) {
}
