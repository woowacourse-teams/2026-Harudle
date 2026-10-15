package com.harudle.generation.diary.service.exception;

/** Provider failure details normalized by adapters, independently of API error codes. */
public enum AiProviderFailureType {

    UNKNOWN,
    NETWORK_ERROR,
    TRANSIENT_SERVER_ERROR,
    AUTH_CONFIGURATION_ERROR,
    REQUEST_REJECTED,
    CANCELLED
}
