package com.harudle.generation.adapter.out.storyboard;

import java.io.IOException;
import java.time.Duration;
import java.util.Objects;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okio.BufferedSink;

/** Transport defaults for a single storyboard attempt; image clients do not use these defaults. */
public final class StoryboardHttpTransport {

    private StoryboardHttpTransport() {
    }

    public static OkHttpClient.Builder builder(Duration timeout) {
        Objects.requireNonNull(timeout);
        if (timeout.compareTo(Duration.ofMillis(1)) < 0
                || timeout.compareTo(Duration.ofMillis(Integer.MAX_VALUE)) > 0) {
            throw new IllegalArgumentException("스토리보드 HTTP 제한 시간은 1ms 이상, 2147483647ms 이하여야 합니다.");
        }
        return new OkHttpClient.Builder()
                .connectTimeout(Duration.ofSeconds(10))
                .callTimeout(timeout)
                .readTimeout(timeout)
                .writeTimeout(timeout)
                .retryOnConnectionFailure(false)
                .followRedirects(false)
                .followSslRedirects(false)
                .addInterceptor(chain -> chain.proceed(singleAttempt(chain.request())));
    }

    private static Request singleAttempt(Request request) {
        RequestBody body = request.body();
        if (body == null || body.isOneShot()) {
            return request;
        }
        // OkHttp can follow a 503 with Retry-After: 0 even when connection retries are disabled.
        return request.newBuilder().method(request.method(), new SingleAttemptBody(body)).build();
    }

    private static final class SingleAttemptBody extends RequestBody {

        private final RequestBody delegate;

        private SingleAttemptBody(RequestBody delegate) {
            this.delegate = delegate;
        }

        @Override
        public MediaType contentType() {
            return delegate.contentType();
        }

        @Override
        public long contentLength() throws IOException {
            return delegate.contentLength();
        }

        @Override
        public void writeTo(BufferedSink sink) throws IOException {
            delegate.writeTo(sink);
        }

        @Override
        public boolean isOneShot() {
            return true;
        }

        @Override
        public boolean isDuplex() {
            return delegate.isDuplex();
        }
    }
}
