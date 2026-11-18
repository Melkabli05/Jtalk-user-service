package com.jtalk.userservice.repository;

import com.jtalk.userservice.domain.entity.User;
import com.jtalk.userservice.domain.model.FollowEdge;
import io.micronaut.data.annotation.Query;
import io.micronaut.data.jdbc.annotation.JdbcRepository;
import io.micronaut.data.model.query.builder.sql.Dialect;
import io.micronaut.data.repository.GenericRepository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The follow graph in PostgreSQL, which is its source of truth. The {@code follows} table has a composite key, so it is written with
 * plain SQL rather than mapped as an entity.
 */
@JdbcRepository(dialect = Dialect.POSTGRES)
public interface FollowRepository extends GenericRepository<User, UUID> {

    /** @return 1 if the follow was made, 0 if it already existed */
    @Query(value = """
            INSERT INTO user_service.follows (follower_id, following_id, created_at)
            VALUES (:followerId, :followingId, :createdAt)
            ON CONFLICT DO NOTHING
            """, nativeQuery = true)
    int follow(UUID followerId, UUID followingId, Instant createdAt);

    @Query(value = "DELETE FROM user_service.follows WHERE follower_id = :followerId AND following_id = :followingId", nativeQuery = true)
    int unfollow(UUID followerId, UUID followingId);

    /** Removes the follows between two users in both directions: what a block does. */
    @Query(value = """
            DELETE FROM user_service.follows
            WHERE (follower_id = :a AND following_id = :b) OR (follower_id = :b AND following_id = :a)
            """, nativeQuery = true)
    int deleteBetween(UUID a, UUID b);

    /** A chunk of the accounts the user follows, newest first, strictly after the given position. */
    @Query(value = """
            SELECT following_id AS user_id, created_at FROM user_service.follows
            WHERE follower_id = :userId AND (created_at, following_id) < (:afterCreatedAt, :afterId)
            ORDER BY created_at DESC, following_id DESC
            LIMIT :limit
            """, nativeQuery = true)
    List<FollowEdge> findFollowingAfter(UUID userId, Instant afterCreatedAt, UUID afterId, int limit);

    /** A chunk of the user's followers, newest first, strictly after the given position. */
    @Query(value = """
            SELECT follower_id AS user_id, created_at FROM user_service.follows
            WHERE following_id = :userId AND (created_at, follower_id) < (:afterCreatedAt, :afterId)
            ORDER BY created_at DESC, follower_id DESC
            LIMIT :limit
            """, nativeQuery = true)
    List<FollowEdge> findFollowersAfter(UUID userId, Instant afterCreatedAt, UUID afterId, int limit);
}
