-- Indexes shaped by EXPLAIN ANALYZE on 500k users / 1.2M language rows / 1.6M follows (see "Performance" in the README).

-- Matchmaking walks active users newest-first and probes the two language rows of each, stopping after one page. Without this index
-- the planner found EVERY matching user, joined and sorted them all, and kept 21 (147 ms for a common pair; now 4 ms).
CREATE INDEX idx_users_active_newest ON users (created_at DESC, id DESC) WHERE status = 'ACTIVE';

-- Typeahead search is a plain range on a C-collated index: `username COLLATE "C" >= 'sa' AND < 'sa<max>'`, ordered by the same
-- expression. A range (unlike LIKE with a bind parameter) is usable by any prepared-statement plan, including the generic one a
-- long-lived connection falls back to, which turned a no-match prefix into a full scan (299 ms; now 0.03 ms). Active users only, so the
-- indexes stay small. The superseded text_pattern_ops indexes go.
CREATE INDEX idx_users_username_c     ON users ((username COLLATE "C"))                 WHERE status = 'ACTIVE';
CREATE INDEX idx_users_display_name_c ON users ((lower(display_name) COLLATE "C"), id)  WHERE status = 'ACTIVE';
DROP INDEX idx_users_username_prefix;
DROP INDEX idx_users_display_name_prefix;

-- Rebuilding a popular account's followers streams them newest-first in chunks keyed on (created_at, follower_id). With both columns in
-- the index the chunk is an index-only scan (21 ms -> 0.27 ms); before, it read the heap and re-sorted every chunk.
DROP INDEX idx_follows_following;
CREATE INDEX idx_follows_following ON follows (following_id, created_at DESC, follower_id DESC);
