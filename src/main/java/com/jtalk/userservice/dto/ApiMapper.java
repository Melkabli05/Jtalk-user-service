package com.jtalk.userservice.dto;

import com.jtalk.userservice.domain.entity.User;
import com.jtalk.userservice.domain.entity.UserLanguage;
import com.jtalk.userservice.dto.response.LanguageEntryResponse;
import com.jtalk.userservice.dto.response.LanguageMatrixResponse;
import com.jtalk.userservice.dto.response.UserProfileResponse;
import com.jtalk.userservice.dto.response.UserSummaryResponse;
import org.mapstruct.Mapper;
import org.mapstruct.MappingConstants;
import org.mapstruct.ReportingPolicy;

import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * Domain -> API mappings, generated at compile time by MapStruct. A DTO field with no source fails the build
 * ({@code unmappedTargetPolicy = ERROR}) instead of silently going out empty.
 */
@Mapper(componentModel = MappingConstants.ComponentModel.JAKARTA, unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface ApiMapper {

    UserProfileResponse toProfile(User user);

    UserSummaryResponse toSummary(User user);

    List<UserSummaryResponse> toSummaries(List<User> users);

    LanguageEntryResponse toEntry(UserLanguage language);

    default LanguageMatrixResponse toMatrix(UUID userId, List<UserLanguage> languages) {
        Comparator<String> byCode = Comparator.naturalOrder();
        return new LanguageMatrixResponse(userId,
                languages.stream().filter(UserLanguage::isNative).map(UserLanguage::languageCode).sorted(byCode).toList(),
                languages.stream().filter(language -> !language.isNative())
                        .sorted(Comparator.comparing(UserLanguage::languageCode))
                        .map(this::toEntry).toList());
    }
}
