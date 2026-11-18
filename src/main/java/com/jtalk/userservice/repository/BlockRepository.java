package com.jtalk.userservice.repository;

import com.jtalk.userservice.domain.entity.User;
import io.micronaut.data.annotation.Query;
import io.micronaut.data.jdbc.annotation.JdbcRepository;
import io.micronaut.data.model.query.builder.sql.Dialect;
import io.micronaut.data.repository.GenericRepository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Blocks, written with plain SQL for the same reason as the follows: the table has a composite key. */
@JdbcRepository(dialect = Dialect.POSTGRES)
public interface BlockRepository extends GenericRepository<User, UUID> {

    @Query(value = """
            INSERT INTO user_service.blocks (blocker_id, blocked_id, created_at)
            VALUES (:blockerId, :blockedId, :createdAt)
            ON CONFLICT DO NOTHING
            """, nativeQuery = true)
    int block(UUID blockerId, UUID blockedId, Instant createdAt);

    @Query(value = "DELETE FROM user_service.blocks WHERE blocker_id = :blockerId AND blocked_id = :blockedId", nativeQuery = true)
    int unblock(UUID blockerId, UUID blockedId);

    /** Whether either of the two has blocked the other. */
    @Query(value = """
            SELECT EXISTS (SELECT 1 FROM user_service.blocks
                           WHERE (blocker_id = :a AND blocked_id = :b) OR (blocker_id = :b AND blocked_id = :a))
            """, nativeQuery = true)
    boolean existsBetween(UUID a, UUID b);

    /** Who the user has blocked, most recent first. */
    @Query(value = """
            SELECT blocked_id FROM user_service.blocks
            WHERE blocker_id = :blockerId
            ORDER BY created_at DESC, blocked_id DESC
            LIMIT :limit
            """, nativeQuery = true)
    List<UUID> findBlockedIds(UUID blockerId, int limit);
}
