import { useCallback } from 'react';
import { usePostHog } from '@posthog/react';
import type { PostHog } from 'posthog-js';
import { RequestError } from '../shared/api';
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

      if (error instanceof RequestError) {
        const { code, status, traceId } = error.problem;

        if (code === 'INVALID_REFRESH_TOKEN' || code === 'SHARE_NOT_FOUND') {
          return;
        }

        // RequestError.message에는 서버의 detail 또는 errors가 담길 수 있다.
        const safeError = new Error(`API request failed: ${code}`);
        safeError.name = 'RequestError';

        posthog.captureException(safeError, {
          ...properties,
          error_code: code,
          http_status: status,
          ...(traceId !== undefined ? { trace_id: traceId } : {}),
        });
        return;
      }

      posthog.captureException(error, properties);
    },
    [posthog],
  );

  return { captureError };
};
