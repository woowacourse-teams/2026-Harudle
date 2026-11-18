package com.harudle.feed.presentation;

import com.harudle.common.error.ErrorType;
import com.harudle.common.error.ProblemDetailFactory;
import com.harudle.feed.service.exception.FeedPreviewCapacityExceededException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes = FeedPreviewController.class)
class FeedPreviewExceptionHandler {

    private final ProblemDetailFactory problems;

    FeedPreviewExceptionHandler(ProblemDetailFactory problems) {
        this.problems = problems;
    }

    @ExceptionHandler(FeedPreviewCapacityExceededException.class)
    ResponseEntity<ProblemDetail> capacityExceeded(HttpServletRequest request) {
        ErrorType error = ErrorType.FEED_PREVIEW_CAPACITY_EXCEEDED;
        return ResponseEntity.status(error.status()).cacheControl(CacheControl.noStore())
                .header(HttpHeaders.RETRY_AFTER, "5").body(problems.create(error, request));
    }
}
