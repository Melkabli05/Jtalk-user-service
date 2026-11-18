package com.jtalk.userservice.domain.event;

import com.jtalk.userservice.domain.entity.User;

/**
 * A profile was created or changed (published inside the transaction). After the commit the cached copy is replaced and the change is
 * announced on Redis, so other services drop what they cached about this user.
 */
public record UserProfileUpdatedEvent(User user) {
}
