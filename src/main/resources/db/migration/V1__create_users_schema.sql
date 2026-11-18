-- Flyway runs this in the "user_service" schema (application.yml), so the tables need no prefix here.

-- Identity and profile. The id is the subject of the user's JWT, chosen upstream, so it has no default.
CREATE TABLE users (
    id               UUID         PRIMARY KEY,
    -- Lowercase on purpose: uniqueness and prefix search then need no case folding.
    username         VARCHAR(30)  NOT NULL UNIQUE CHECK (username ~ '^[a-z0-9_.]{3,30}$'),
    display_name     VARCHAR(50)  NOT NULL,
    bio              VARCHAR(300),
    avatar_url       VARCHAR(500),
    country_code     CHAR(2)      NOT NULL,          -- ISO 3166-1 alpha-2
    native_language  VARCHAR(10)  NOT NULL,          -- the primary native language; it always has a NATIVE row in user_languages
    status           VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'SUSPENDED')),
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at       TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

-- The language matrix: any number of native and learning languages per user. One row per user and language, so a language
-- cannot be both native and learning.
CREATE TABLE user_languages (
    id                UUID         PRIMARY KEY,
    user_id           UUID         NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    language_code     VARCHAR(10)  NOT NULL,
    language_type     VARCHAR(10)  NOT NULL CHECK (language_type IN ('NATIVE', 'LEARNING')),
    proficiency_level VARCHAR(12)  CHECK (proficiency_level IN ('BEGINNER', 'INTERMEDIATE', 'ADVANCED', 'FLUENT')),
    UNIQUE (user_id, language_code)
);

-- Asymmetric follows (Clubhouse / Instagram style).
CREATE TABLE follows (
    follower_id   UUID         NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    following_id  UUID         NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    PRIMARY KEY (follower_id, following_id),
    CHECK (follower_id <> following_id)
);

-- Blocks hide both users from each other in discovery and forbid following in either direction.
CREATE TABLE blocks (
    blocker_id  UUID         NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    blocked_id  UUID         NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    PRIMARY KEY (blocker_id, blocked_id),
    CHECK (blocker_id <> blocked_id)
);

-- Matchmaking: "who is native in X" and "who is learning Y". user_id is in the index so the lookups never touch the table.
CREATE INDEX idx_user_languages_matrix ON user_languages (language_code, language_type, user_id);

-- Typeahead search by prefix. The UNIQUE index on username cannot serve LIKE 'abc%' under a non-C collation; these can.
CREATE INDEX idx_users_username_prefix     ON users (username text_pattern_ops);
CREATE INDEX idx_users_display_name_prefix ON users (lower(display_name) text_pattern_ops);

-- follows is read by follower_id through its primary key; this serves "who follows X" and the cascade delete.
CREATE INDEX idx_follows_following ON follows (following_id, created_at DESC);
CREATE INDEX idx_blocks_blocked    ON blocks (blocked_id);
