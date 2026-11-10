package com.harudle.common.error;

import org.springframework.context.annotation.Import;

/** Explicit error handling imports for the maintenance application; no application-wide scan. */
@Import({TraceIdConfiguration.class, TraceIdFilter.class, ProblemDetailFactory.class, ApiExceptionLogger.class,
        GlobalExceptionHandler.class})
public class ImageRecoveryErrorConfiguration {
}
