package com.jtalk.userservice.config;

/** Limits and formats shared by request validation and the database constraints. */
public final class UserConstants {

    /** The PostgreSQL schema this service owns (the Neon database is shared with the live communication service). */
    public static final String SCHEMA = "user_service";

    /** Lowercase letters, digits, underscore and dot: the same rule the database checks. */
    public static final String USERNAME_PATTERN = "^[a-z0-9_.]{3,30}$";
    /** A language tag such as {@code en}, {@code ar} or {@code pt-BR}: lowercase language, uppercase region. */
    public static final String LANGUAGE_PATTERN = "^[a-z]{2,3}(-[A-Z]{2})?$";
    /** ISO 3166-1 alpha-2, e.g. {@code MA}. */
    public static final String COUNTRY_PATTERN = "^[A-Z]{2}$";
    /** An https URL, or empty (which clears the avatar). Nothing else, so a profile can never carry a {@code javascript:} link. */
    public static final String AVATAR_PATTERN = "^(https://\\S+)?$";
    /** At least one visible character. */
    public static final String NOT_BLANK_PATTERN = ".*\\S.*";

    public static final int MAX_PAGE_SIZE = 100;
    /** How many profiles one batch lookup may resolve: a voice room with a full stage and a few hundred listeners. */
    public static final int MAX_BATCH_IDS = 500;
    public static final int MAX_SEARCH_RESULTS = 50;
    public static final int MAX_BLOCKS_LISTED = 500;

    private UserConstants() {
    }
}
