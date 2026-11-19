-- When the username last changed, so it can be changed again only after a while. NULL until the first change.
ALTER TABLE users ADD COLUMN username_changed_at TIMESTAMPTZ;

-- A picture the user uploaded, kept with the profile it belongs to (and gone with it: ON DELETE CASCADE). The browser shrinks it to a
-- small square before sending, so a row is tens of kilobytes. The version changes with every upload and is part of the picture's address,
-- so a browser can cache each version for good.
CREATE TABLE user_avatars (
    user_id       UUID         PRIMARY KEY REFERENCES users (id) ON DELETE CASCADE,
    content_type  VARCHAR(20)  NOT NULL CHECK (content_type IN ('image/jpeg', 'image/png')),
    image         BYTEA        NOT NULL,
    version       BIGINT       NOT NULL
);
