package com.jtalk.userservice.repository;

import com.jtalk.userservice.domain.entity.UserAvatar;
import io.micronaut.data.annotation.Query;
import io.micronaut.data.jdbc.annotation.JdbcRepository;
import io.micronaut.data.model.query.builder.sql.Dialect;
import io.micronaut.data.repository.CrudRepository;

import java.util.UUID;

@JdbcRepository(dialect = Dialect.POSTGRES)
public interface UserAvatarRepository extends CrudRepository<UserAvatar, UUID> {

    /** The first picture is inserted, a later one replaces it: one row per user. */
    @Query("""
            INSERT INTO user_service.user_avatars (user_id, content_type, image, version)
            VALUES (:userId, :contentType, :image, :version)
            ON CONFLICT (user_id) DO UPDATE
               SET content_type = EXCLUDED.content_type, image = EXCLUDED.image, version = EXCLUDED.version
            """)
    void upsert(UUID userId, String contentType, byte[] image, long version);
}
