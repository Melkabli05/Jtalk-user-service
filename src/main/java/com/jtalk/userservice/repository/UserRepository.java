package com.jtalk.userservice.repository;

import com.jtalk.userservice.domain.entity.User;
import io.micronaut.data.annotation.Query;
import io.micronaut.data.jdbc.annotation.JdbcRepository;
import io.micronaut.data.model.query.builder.sql.Dialect;
import io.micronaut.data.repository.CrudRepository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

@JdbcRepository(dialect = Dialect.POSTGRES)
public interface UserRepository extends CrudRepository<User, UUID> {

    boolean existsByUsername(String username);

    List<User> findByIdIn(Collection<UUID> ids);

    /** Neither side may have blocked the other: a block hides both users from each other everywhere they are discovered. */
    String NOT_BLOCKED = """
            AND NOT EXISTS (SELECT 1 FROM user_service.blocks b
                            WHERE (b.blocker_id = :viewerId AND b.blocked_id = u.id)
                               OR (b.blocker_id = u.id AND b.blocked_id = :viewerId))
            """;

    /**
     * Typeahead: active users whose username or display name starts with the prefix. {@code prefix} is already lowercased and escaped
     * with {@code !} for LIKE; the {@code text_pattern_ops} indexes serve both comparisons.
     */
    @Query(value = """
            SELECT u.* FROM user_service.users u
            WHERE u.status = 'ACTIVE' AND u.id <> :viewerId
              AND (u.username LIKE :prefix ESCAPE '!' OR lower(u.display_name) LIKE :prefix ESCAPE '!')
            """ + NOT_BLOCKED + """
            ORDER BY u.username
            LIMIT :limit
            """, nativeQuery = true)
    List<User> searchByPrefix(UUID viewerId, String prefix, int limit);

    /**
     * Language-exchange partners: users who are native in what the viewer is learning AND are learning what the viewer is native in.
     * Newest first, with the id as tie-breaker, so a page boundary is stable.
     */
    String MATCH = """
            SELECT u.* FROM user_service.users u
            WHERE u.status = 'ACTIVE' AND u.id <> :viewerId
              AND EXISTS (SELECT 1 FROM user_service.user_languages n
                          WHERE n.user_id = u.id AND n.language_type = 'NATIVE' AND n.language_code = :partnerNative)
              AND EXISTS (SELECT 1 FROM user_service.user_languages l
                          WHERE l.user_id = u.id AND l.language_type = 'LEARNING' AND l.language_code = :partnerLearning)
            """ + NOT_BLOCKED;

    @Query(value = MATCH + """
            ORDER BY u.created_at DESC, u.id DESC
            LIMIT :limit
            """, nativeQuery = true)
    List<User> findMatches(UUID viewerId, String partnerNative, String partnerLearning, int limit);

    /** Keyset ("seek") pagination: the next page starts strictly after the last user of the previous one. */
    @Query(value = MATCH + """
              AND (u.created_at, u.id) < (:afterCreatedAt, :afterId)
            ORDER BY u.created_at DESC, u.id DESC
            LIMIT :limit
            """, nativeQuery = true)
    List<User> findMatchesAfter(UUID viewerId, String partnerNative, String partnerLearning, Instant afterCreatedAt, UUID afterId, int limit);
}
