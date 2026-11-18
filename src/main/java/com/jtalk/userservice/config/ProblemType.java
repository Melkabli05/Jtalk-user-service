package com.jtalk.userservice.config;

import io.micronaut.http.HttpStatus;
import io.micronaut.problem.HttpStatusType;
import org.zalando.problem.Problem;
import org.zalando.problem.ThrowableProblem;

import java.net.URI;
import java.util.Locale;

/**
 * Every API error in one place: HTTP status and title are declared once per type, and the RFC 9457
 * {@code type} is derived from the constant's name ({@code USERNAME_TAKEN} -> {@code ...:username-taken}) so clients
 * can switch on a stable code. Micronaut's Problem module renders the thrown result as {@code application/problem+json}.
 * <pre>throw ProblemType.USERNAME_TAKEN.because("The username 'sara' is taken");</pre>
 */
public enum ProblemType {
    USER_NOT_FOUND(HttpStatus.NOT_FOUND, "User not found"),
    PROFILE_ALREADY_EXISTS(HttpStatus.CONFLICT, "Profile already exists"),
    USERNAME_TAKEN(HttpStatus.CONFLICT, "Username is taken"),
    CANNOT_FOLLOW_SELF(HttpStatus.UNPROCESSABLE_ENTITY, "You cannot follow yourself"),
    CANNOT_BLOCK_SELF(HttpStatus.UNPROCESSABLE_ENTITY, "You cannot block yourself"),
    FOLLOW_NOT_ALLOWED(HttpStatus.FORBIDDEN, "You cannot follow this user"),
    FOLLOW_LIMIT_REACHED(HttpStatus.UNPROCESSABLE_ENTITY, "Following limit reached"),
    LANGUAGE_CONFLICT(HttpStatus.CONFLICT, "Language is already listed another way"),
    LANGUAGE_NOT_FOUND(HttpStatus.NOT_FOUND, "Language not listed"),
    PRIMARY_LANGUAGE_REQUIRED(HttpStatus.UNPROCESSABLE_ENTITY, "Your primary native language cannot be removed"),
    TOO_MANY_LANGUAGES(HttpStatus.UNPROCESSABLE_ENTITY, "Too many languages"),
    NO_LEARNING_LANGUAGE(HttpStatus.UNPROCESSABLE_ENTITY, "No learning language to match on");

    private static final String TYPE_PREFIX = "urn:jtalk:user:problem:";

    private final HttpStatus status;
    private final String title;
    private final URI type;

    ProblemType(HttpStatus status, String title) {
        this.status = status;
        this.title = title;
        this.type = URI.create(TYPE_PREFIX + name().toLowerCase(Locale.ROOT).replace('_', '-'));
    }

    public ThrowableProblem because(String detail) {
        return Problem.builder()
                .withType(type)
                .withTitle(title)
                .withStatus(new HttpStatusType(status))
                .withDetail(detail)
                .build();
    }
}
