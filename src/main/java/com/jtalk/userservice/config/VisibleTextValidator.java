package com.jtalk.userservice.config;

import io.micronaut.core.annotation.AnnotationValue;
import io.micronaut.core.annotation.NonNull;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.validation.validator.constraints.ConstraintValidator;
import io.micronaut.validation.validator.constraints.ConstraintValidatorContext;
import jakarta.inject.Singleton;

@Singleton
public class VisibleTextValidator implements ConstraintValidator<VisibleText, String> {

    @Override
    public boolean isValid(
            @Nullable String value,
            @NonNull AnnotationValue<VisibleText> annotationMetadata,
            @NonNull ConstraintValidatorContext context) {
        if (value == null) {
            return true;
        }
        boolean allowEmpty = annotationMetadata.booleanValue("allowEmpty").orElse(false);
        if (allowEmpty && value.isEmpty()) {
            return true;
        }
        boolean multiline = annotationMetadata.booleanValue("multiline").orElse(false);
        String cleaned = multiline ? TextSanitizer.cleanMultiLine(value) : TextSanitizer.cleanSingleLine(value);
        return cleaned != null && TextSanitizer.isVisible(cleaned);
    }
}
