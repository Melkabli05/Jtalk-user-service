package com.jtalk.userservice.controller;

import io.micronaut.core.bind.annotation.Bindable;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Binds a {@code UUID} controller parameter to the id of the authenticated caller (the JWT subject). */
@Documented
@Bindable
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.PARAMETER)
@interface CurrentUser {
}
