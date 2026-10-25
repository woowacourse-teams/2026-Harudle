package com.harudle.generation.adapter.out.gemini.client;

import com.harudle.generation.diary.service.port.StoryboardAttemptContext;
import com.harudle.generation.diary.service.port.StoryboardAttemptContext.CancellationRegistration;
import java.io.IOException;
import java.util.Objects;
import java.util.function.Supplier;
import okhttp3.Call;
import okhttp3.EventListener;

/** Bridges the synchronous SDK call to its request-specific OkHttp call inside the adapter. */
public final class GeminiStoryboardCallScope implements EventListener.Factory {

    private final ThreadLocal<StoryboardAttemptContext> currentContext = new ThreadLocal<>();

    public <T> T withContext(StoryboardAttemptContext context, Supplier<T> action) {
        Objects.requireNonNull(context).checkActive();
        Objects.requireNonNull(action);
        StoryboardAttemptContext previous = currentContext.get();
        currentContext.set(context);
        try {
            return action.get();
        } finally {
            if (previous == null) {
                currentContext.remove();
            } else {
                currentContext.set(previous);
            }
        }
    }

    @Override
    public EventListener create(Call call) {
        StoryboardAttemptContext context = currentContext.get();
        // A Call is still being constructed here. Registration may immediately cancel it, so wait for callStart.
        return context == null ? EventListener.NONE : new AttemptListener(context);
    }

    private static final class AttemptListener extends EventListener {

        private final StoryboardAttemptContext context;
        private CancellationRegistration registration;

        private AttemptListener(StoryboardAttemptContext context) {
            this.context = context;
        }

        @Override
        public void callStart(Call call) {
            registration = context.onCancellation(call::cancel);
        }

        @Override
        public void callEnd(Call call) {
            unregister();
        }

        @Override
        public void callFailed(Call call, IOException exception) {
            unregister();
        }

        private void unregister() {
            if (registration != null) {
                registration.close();
            }
        }
    }
}
