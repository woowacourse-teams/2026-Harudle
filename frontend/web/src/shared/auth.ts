import { API_BASE_URL } from './api';

let accessToken: string | null = null;
let restoreLoginRequest: Promise<boolean> | null = null;

const isRecord = (value: unknown): value is Record<string, unknown> => {
  return typeof value === 'object' && value !== null;
};

const requestAccessToken = async (): Promise<boolean> => {
  try {
    if (
      process.env.NODE_ENV === 'development' &&
      sessionStorage.getItem('mockLoggedIn') !== 'true'
    ) {
      return false;
    }

    const csrfResponse = await fetch(`${API_BASE_URL}/auth/csrf`, {
      credentials: 'include',
    });

    if (!csrfResponse.ok) {
      return false;
    }

    const csrfData: unknown = await csrfResponse.json();

    if (!isRecord(csrfData) || typeof csrfData.token !== 'string') {
      return false;
    }

    const refreshResponse = await fetch(`${API_BASE_URL}/auth/refresh`, {
      method: 'POST',
      credentials: 'include',
      headers: {
        'X-XSRF-TOKEN': csrfData.token,
      },
    });

    if (!refreshResponse.ok) {
      return false;
    }

    const refreshData: unknown = await refreshResponse.json();

    if (!isRecord(refreshData) || typeof refreshData.accessToken !== 'string') {
      return false;
    }

    accessToken = refreshData.accessToken;
    return true;
  } catch {
    return false;
  }
};

export const restoreLogin = (): Promise<boolean> => {
  if (accessToken) {
    return Promise.resolve(true);
  }

  if (!restoreLoginRequest) {
    restoreLoginRequest = requestAccessToken().finally(() => {
      restoreLoginRequest = null;
    });
  }

  return restoreLoginRequest;
};

export const authFetch = (
  input: RequestInfo | URL,
  init: RequestInit = {},
): Promise<Response> => {
  const headers = new Headers(init.headers);

  if (accessToken) {
    headers.set('Authorization', `Bearer ${accessToken}`);
  }

  return fetch(input, { ...init, headers });
};

export const logout = async (): Promise<void> => {
  const response = await authFetch(`${API_BASE_URL}/auth/logout`, {
    method: 'POST',
    credentials: 'include',
  });

  if (!response.ok) {
    throw new Error('로그아웃에 실패했습니다.');
  }

  accessToken = null;

  if (process.env.NODE_ENV === 'development') {
    sessionStorage.removeItem('mockLoggedIn');
  }
};
