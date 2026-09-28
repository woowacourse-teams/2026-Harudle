import { useCallback } from 'react';
import { usePostHog } from '@posthog/react';
import type { PostHog } from 'posthog-js';
import { isPostHogEnabled } from './posthog';

interface ErrorTracking {
  readonly captureError: (
    error: Error,
    properties?: Parameters<PostHog['captureException']>[1],
  ) => void;
}

export const useErrorTracking = (): ErrorTracking => {
  const posthog = usePostHog();

  const captureError = useCallback<ErrorTracking['captureError']>(
    (error, properties): void => {
      if (!isPostHogEnabled) {
        return;
      }

      posthog.captureException(error, properties);
    },
    [posthog],
  );

  return { captureError };
};
