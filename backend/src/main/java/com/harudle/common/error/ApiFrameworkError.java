package com.harudle.common.error;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/** Documents a Spring framework error whose code is determined by its HTTP status. */
@Retention(RetentionPolicy.RUNTIME)
public @interface ApiFrameworkError {

    int status();

    /** The reason passed to ResponseStatusException, used as the example detail. */
    String detail();

    /** Distinguishes examples when one status has several failure reasons. */
    String name() default "";
}
