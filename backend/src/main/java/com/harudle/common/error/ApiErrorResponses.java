package com.harudle.common.error;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Declares the service error codes that a controller method can return. */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface ApiErrorResponses {

    ErrorType[] value() default {};

    ApiFrameworkError[] framework() default {};
}
