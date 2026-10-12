import { useEffect, useState } from 'react';
import { RequestError } from './shared/api';
import { restoreAccessToken } from './shared/auth';
import { useErrorTracking } from './posthog/useErrorTracking';

interface SessionRestoreState {
  readonly isRestoring: boolean;
}

const useSessionRestore = (): SessionRestoreState => {
  const { captureError } = useErrorTracking();
  let hasLoginHistory = false;

  try {
    hasLoginHistory =
      localStorage.getItem('harudle.has-ever-logged-in') !== null ||
      localStorage.getItem('harudle.has-completed-oauth') !== null;
  } catch {
    // 로그인 이력을 확인할 수 없으면 공개 조회를 허용한다.
  }
  const [isRestoring, setIsRestoring] = useState(hasLoginHistory);

  useEffect(() => {
    if (!hasLoginHistory) return;
    let isActive = true;

    try {
      // 기존 사용자의 이력을 옮겨 로그아웃 후에도 로그인 경험을 유지한다.
      if (localStorage.getItem('harudle.has-ever-logged-in') === null) {
        localStorage.setItem('harudle.has-ever-logged-in', 'true');
      }
    } catch {
      // 이력 저장 실패가 세션 복원을 막지 않게 한다.
    }

    void restoreAccessToken()
      .catch((error: unknown): void => {
        if (
          error instanceof RequestError &&
          error.problem.code === 'INVALID_REFRESH_TOKEN'
        ) {
          return;
        }
        if (error instanceof Error) {
          captureError(error, {
            feature: 'authentication',
            operation: 'restore_session',
          });
        }
      })
      .finally((): void => {
        if (isActive) setIsRestoring(false);
      });

    return (): void => {
      isActive = false;
    };
  }, [hasLoginHistory, captureError]);

  return { isRestoring };
};

export default useSessionRestore;
