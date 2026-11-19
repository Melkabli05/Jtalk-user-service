package com.jtalk.userservice.repository;

import com.jtalk.userservice.domain.entity.User;
import io.micronaut.data.annotation.Query;
import io.micronaut.data.jdbc.annotation.JdbcRepository;
import io.micronaut.data.model.query.builder.sql.Dialect;
import io.micronaut.data.repository.CrudRepository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@JdbcRepository(dialect = Dialect.POSTGRES)
public interface UserRepository extends CrudRepository<User, UUID> {

    boolean existsByUsername(String username);

    /** When this user last changed their username; empty if they never did. */
    @Query("SELECT username_changed_at FROM user_service.users WHERE id = :userId")
    Optional<Instant> findUsernameChangedAt(UUID userId);

    @Query("UPDATE user_service.users SET username_changed_at = NOW() WHERE id = :userId")
    void markUsernameChanged(UUID userId);

    List<User> findByIdIn(Collection<UUID> ids);

    /** Neither side may have blocked the other: a block hides both users from each other everywhere they are discovered. */
    String NOT_BLOCKED = """
            AND NOT EXISTS (SELECT 1 FROM user_service.blocks b
                            WHERE (b.blocker_id = :viewerId AND b.blocked_id = u.id)
                               OR (b.blocker_id = u.id AND b.blocked_id = :viewerId))
            """;

    /**
     * Typeahead by handle: active users whose username lies in {@code [rangeStart, rangeEnd)}, which is "starts with the prefix". It is a
     * range on a C-collated index (V3) and not a LIKE: a range can be served by any prepared-statement plan, while LIKE with a bind
     * parameter falls back to scanning the whole table once the plan turns generic. Ordered by the same expression, so the index returns
     * the first {@code limit} rows and stops.
     */
    @Query(value = """
            SELECT u.* FROM user_service.users u
            WHERE u.status = 'ACTIVE' AND u.id <> :viewerId
              AND u.username COLLATE "C" >= :rangeStart AND u.username COLLATE "C" < :rangeEnd
            """ + NOT_BLOCKED + """
            ORDER BY u.username COLLATE "C"
            LIMIT :limit
            """, nativeQuery = true)
    List<User> searchByUsernamePrefix(UUID viewerId, String rangeStart, String rangeEnd, int limit);

    /** The same by display name (compared lowercased), for a person whose handle does not start with what was typed. */
    @Query(value = """
            SELECT u.* FROM user_service.users u
            WHERE u.status = 'ACTIVE' AND u.id <> :viewerId
              AND lower(u.display_name) COLLATE "C" >= :rangeStart AND lower(u.display_name) COLLATE "C" < :rangeEnd
            """ + NOT_BLOCKED + """
            ORDER BY lower(u.display_name) COLLATE "C", u.id
            LIMIT :limit
            """, nativeQuery = true)
    List<User> searchByDisplayNamePrefix(UUID viewerId, String rangeStart, String rangeEnd, int limit);

    /**
     * Language-exchange partners: users who are native in ANY of {@code :partnerNatives} (what the viewer is learning) AND are learning
     * ANY of {@code :partnerLearns} (what the viewer speaks). The lists are a person's languages, a handful at most. A user is a partner
     * once however many languages they share (EXISTS, not a join). Newest first, with the id as tie-breaker, so a page boundary is stable.
     */
    String MATCH = """
            SELECT u.* FROM user_service.users u
            WHERE u.status = 'ACTIVE' AND u.id <> :viewerId
              AND EXISTS (SELECT 1 FROM user_service.user_languages n
                          WHERE n.user_id = u.id AND n.language_type = 'NATIVE' AND n.language_code IN (:partnerNatives))
              AND EXISTS (SELECT 1 FROM user_service.user_languages l
                          WHERE l.user_id = u.id AND l.language_type = 'LEARNING' AND l.language_code IN (:partnerLearns))
            """ + NOT_BLOCKED;

    @Query(value = MATCH + """
            ORDER BY u.created_at DESC, u.id DESC
            LIMIT :limit
            """, nativeQuery = true)
    List<User> findMatches(UUID viewerId, Collection<String> partnerNatives, Collection<String> partnerLearns, int limit);

    /** Keyset ("seek") pagination: the next page starts strictly after the last user of the previous one. */
    @Query(value = MATCH + """
              AND (u.created_at, u.id) < (:afterCreatedAt, :afterId)
            ORDER BY u.created_at DESC, u.id DESC
            LIMIT :limit
            """, nativeQuery = true)
    List<User> findMatchesAfter(UUID viewerId, Collection<String> partnerNatives, Collection<String> partnerLearns, Instant afterCreatedAt, UUID afterId, int limit);
}
