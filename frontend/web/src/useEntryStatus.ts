import { useCallback, useEffect, useState } from 'react';
import { RequestError } from './shared/api';
import { restoreAccessToken } from './shared/auth';
import { useErrorTracking } from './posthog/useErrorTracking';

type EntryStatus = 'landing' | 'restoringSession' | 'home' | 'login' | 'error';

interface EntryState {
  readonly status: EntryStatus;
  readonly retry: () => void;
}

const useEntryStatus = (): EntryState => {
  const { captureError } = useErrorTracking();
  let hasLoginHistory = false;

  try {
    hasLoginHistory =
      localStorage.getItem('harudle.has-ever-logged-in') !== null;
  } catch {
    // 저장소에 접근할 수 없으면 랜딩을 보여준다.
  }
  const [status, setStatus] = useState<EntryStatus>(
    hasLoginHistory ? 'restoringSession' : 'landing',
  );

  const checkSession = useCallback((): Promise<void> => {
    return restoreAccessToken()
      .then((): void => {
        setStatus('home');
      })
      .catch((error: unknown): void => {
        if (
          error instanceof RequestError &&
          error.problem.code === 'INVALID_REFRESH_TOKEN'
        ) {
          setStatus('login');
          return;
        }

        if (error instanceof Error) {
          captureError(error, {
            feature: 'authentication',
            operation: 'restore_session',
          });
        }
        setStatus('error');
      });
  }, [captureError]);

  useEffect(() => {
    if (hasLoginHistory) void checkSession();
  }, [hasLoginHistory, checkSession]);

  const retry = (): void => {
    setStatus('restoringSession');
    void checkSession();
  };

  return { status, retry };
};

export default useEntryStatus;
