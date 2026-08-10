package com.harudle.generation.service.exception;

import java.io.Serial;

public final class GenerationUnavailableException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    public GenerationUnavailableException(String message) {
        super(message);
    }
}
