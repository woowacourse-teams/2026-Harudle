import { API_BASE_URL, isProblemDetails, RequestError } from '../../shared/api';
import { isRefreshTokenResponse, setAccessToken } from '../../shared/auth';
import { issueGuestSession, requestGuestCsrfToken } from './guestTrialApi';

export type GuestEntryResult =
  { status: 'authenticated' } | { status: 'guest' };

export const checkGuestEntryAuthentication = async (): Promise<boolean> => {
  const csrfToken = await requestGuestCsrfToken();
  const response = await fetch(`${API_BASE_URL}/auth/refresh`, {
    method: 'POST',
    credentials: 'include',
    headers: {
      'X-XSRF-TOKEN': csrfToken,
    },
  });

  if (!response.ok) {
    const data: unknown = await response.json().catch(() => null);

    if (isProblemDetails(data) && data.code === 'INVALID_REFRESH_TOKEN') {
      setAccessToken(null);
      return false;
    }

    if (isProblemDetails(data)) {
      throw new RequestError(data);
    }

    throw new Error('로그인 상태 확인에 실패했습니다');
  }

  const data: unknown = await response.json();

  if (!isRefreshTokenResponse(data)) {
    throw new Error('RefreshToken 응답 형식이 일치하지 않습니다');
  }

  setAccessToken(data.accessToken);
  return true;
};

interface GuestEntryDependencies {
  checkAuthentication: () => Promise<boolean>;
  createGuestSession: () => Promise<void>;
}

export const createGuestEntryInitializer = ({
  checkAuthentication = checkGuestEntryAuthentication,
  createGuestSession = issueGuestSession,
}: Partial<GuestEntryDependencies> = {}): (() => Promise<GuestEntryResult>) => {
  let initializationRequest: Promise<GuestEntryResult> | null = null;

  return () => {
    if (!initializationRequest) {
      const initialize = async (): Promise<GuestEntryResult> => {
        const isAuthenticated = await checkAuthentication();

        if (isAuthenticated) {
          return { status: 'authenticated' };
        }

        await createGuestSession();
        return { status: 'guest' };
      };

      initializationRequest = initialize().finally(() => {
        initializationRequest = null;
      });
    }

    return initializationRequest;
  };
};

export const initializeGuestEntry = createGuestEntryInitializer();
