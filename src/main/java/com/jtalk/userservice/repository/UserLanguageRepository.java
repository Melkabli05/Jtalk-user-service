package com.jtalk.userservice.repository;

import com.jtalk.userservice.domain.entity.UserLanguage;
import io.micronaut.data.jdbc.annotation.JdbcRepository;
import io.micronaut.data.model.query.builder.sql.Dialect;
import io.micronaut.data.repository.CrudRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@JdbcRepository(dialect = Dialect.POSTGRES)
public interface UserLanguageRepository extends CrudRepository<UserLanguage, UUID> {

    List<UserLanguage> findByUserIdOrderByLanguageCode(UUID userId);

    Optional<UserLanguage> findByUserIdAndLanguageCode(UUID userId, String languageCode);

    long countByUserId(UUID userId);

    int deleteByUserIdAndLanguageCode(UUID userId, String languageCode);
}
