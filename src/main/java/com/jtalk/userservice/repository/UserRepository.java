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

    Optional<User> findByIdForUpdate(UUID id);

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

    /** Both prefix ranges use the C-collated indexes. Display names fill the slots left after matching handles. */
    @Query(value = """
            WITH handles AS MATERIALIZED (
                SELECT u.*, 0 AS match_rank, u.username COLLATE "C" AS match_name
                FROM user_service.users u
                WHERE u.status = 'ACTIVE' AND u.id <> :viewerId
                  AND u.username COLLATE "C" >= :rangeStart AND u.username COLLATE "C" < :rangeEnd
                """ + NOT_BLOCKED + """
                ORDER BY match_name
                LIMIT :limit
            ), names AS (
                SELECT u.*, 1 AS match_rank, lower(u.display_name) COLLATE "C" AS match_name
                FROM user_service.users u
                WHERE u.status = 'ACTIVE' AND u.id <> :viewerId
                  AND lower(u.display_name) COLLATE "C" >= :rangeStart AND lower(u.display_name) COLLATE "C" < :rangeEnd
                  AND u.id NOT IN (SELECT id FROM handles)
                """ + NOT_BLOCKED + """
                ORDER BY match_name, u.id
                LIMIT (:limit - (SELECT count(*) FROM handles))
            )
            SELECT * FROM handles
            UNION ALL
            SELECT * FROM names
            ORDER BY match_rank, match_name, id
            """, nativeQuery = true)
    List<User> searchByPrefix(UUID viewerId, String rangeStart, String rangeEnd, int limit);

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
