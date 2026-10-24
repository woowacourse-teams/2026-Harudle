package com.harudle.generation.adapter.out.s3;

import java.io.IOException;
import java.io.Serial;

final class CwebpConversionException extends IOException {

    @Serial
    private static final long serialVersionUID = 1L;

    private final String failureType;

    CwebpConversionException(int exitCode, String output) {
        super("cwebp 변환 프로세스가 실패했습니다 (exit=" + exitCode + ")"
                + (output.isBlank() ? "." : ": " + output));
        failureType = output.contains("Could not process file")
                || output.contains("Cannot read input picture file")
                ? "CWEBP_INPUT_ERROR" : "CWEBP_PROCESS_ERROR";
    }

    String failureType() {
        return failureType;
    }
}
