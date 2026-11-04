package com.harudle.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

class RequestOperationTimingsTest {

    @AfterEach
    void clearContext() {
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void accumulatesCountsMissingFailuresTotalAndMaxWithoutKeepingIndividualCalls() {
        var request = new MockHttpServletRequest();
        RequestOperationTimings.start(request);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        RequestOperationTimings.record(RequestOperationTimings.Operation.S3_HEAD, 11_000_000, false);
        RequestOperationTimings.record(RequestOperationTimings.Operation.S3_HEAD, 5_000_000, false, true);
        RequestOperationTimings.record(RequestOperationTimings.Operation.R2_HEAD, 7_000_000, true);

        var fields = RequestOperationTimings.finish(request);
        assertThat(fields).containsEntry("s3HeadCount", 2).containsEntry("s3HeadMissingCount", 1)
                .containsEntry("s3HeadDurationMs", 16.0).containsEntry("s3HeadMaxMs", 11.0)
                .containsEntry("r2HeadFailureCount", 1).containsEntry("r2SignCount", 0);
        assertThat(RequestOperationTimings.finish(request)).isEmpty();
    }

    @Test
    void workersWithoutHttpContextDoNotAttachOperationsToTheOriginatingRequest() throws Exception {
        var request = new MockHttpServletRequest();
        RequestOperationTimings.start(request);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        Thread worker = new Thread(() -> RequestOperationTimings.record(
                RequestOperationTimings.Operation.S3_HEAD, 2_000_000, false));
        worker.start();
        worker.join();
        assertThat(RequestOperationTimings.finish(request)).containsEntry("s3HeadCount", 0);
    }

    @Test
    void queryFailureKeepsTheOriginalExceptionAndRecordsIt() {
        var request = new MockHttpServletRequest();
        RequestOperationTimings.start(request);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        var expected = new IllegalStateException("original failure");
        assertThatThrownBy(() -> RequestOperationTimings.measure(RequestOperationTimings.Operation.FEED_QUERY,
                () -> { throw expected; })).isSameAs(expected);
        assertThat(RequestOperationTimings.finish(request)).containsEntry("feedQueryCount", 1)
                .containsEntry("feedQueryFailureCount", 1);
    }

    @Test
    void unavailableRequestDiagnosticsDoNotReplaceTheBusinessOutcome() {
        var request = mock(HttpServletRequest.class);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        when(request.getAttribute(anyString())).thenThrow(new IllegalStateException("request unavailable"));
        assertThat(RequestOperationTimings.measure(RequestOperationTimings.Operation.FEED_QUERY, () -> "ok"))
                .isEqualTo("ok");
        var expected = new IllegalArgumentException("original failure");
        assertThatThrownBy(() -> RequestOperationTimings.measure(RequestOperationTimings.Operation.FEED_QUERY,
                () -> { throw expected; })).isSameAs(expected);
        RequestOperationTimings.returnedImages(1);
    }
}
