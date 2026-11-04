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

    /** 접수 후 생성 실패에만 게스트용 행동 안내를 예시에 사용한다. */
    ErrorType[] guestGenerationFailures() default {};

    ApiFrameworkError[] framework() default {};
}
