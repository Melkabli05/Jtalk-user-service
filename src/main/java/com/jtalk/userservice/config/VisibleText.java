package com.jtalk.userservice.config;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;

import static java.lang.annotation.ElementType.FIELD;
import static java.lang.annotation.ElementType.PARAMETER;
import static java.lang.annotation.ElementType.RECORD_COMPONENT;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

/** Rejects strings that contain only control, format, whitespace, or blank Unicode filler characters. */
@Documented
@Constraint(validatedBy = {})
@Target({FIELD, PARAMETER, RECORD_COMPONENT})
@Retention(RUNTIME)
public @interface VisibleText {

    String message() default "must contain visible characters";

    boolean allowEmpty() default false;

    boolean multiline() default false;

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
