import { API_BASE_URL } from './api';

let accessToken: string | null = null;
let restoreLoginRequest: Promise<boolean> | null = null;

const isRecord = (value: unknown): value is Record<string, unknown> => {
  return typeof value === 'object' && value !== null;
};

const requestCsrfToken = async (): Promise<string | null> => {
  const response = await fetch(`${API_BASE_URL}/auth/csrf`, {
    credentials: 'include',
  });

  if (!response.ok) {
    return null;
  }

  const data: unknown = await response.json();

  return isRecord(data) && typeof data.token === 'string' ? data.token : null;
};

const requestAccessToken = async (): Promise<boolean> => {
  try {
    const csrfToken = await requestCsrfToken();

    if (!csrfToken) {
      return false;
    }

    const refreshResponse = await fetch(`${API_BASE_URL}/auth/refresh`, {
      method: 'POST',
      credentials: 'include',
      headers: {
        'X-XSRF-TOKEN': csrfToken,
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

export const authFetch = async (
  input: RequestInfo | URL,
  init: RequestInit = {},
): Promise<Response> => {
  const headers = new Headers(init.headers);
  const sentAccessToken = accessToken;

  if (sentAccessToken) {
    headers.set('Authorization', `Bearer ${sentAccessToken}`);
  }

  const response = await fetch(input, { ...init, headers });

  if (response.status !== 401) {
    return response;
  }

  if (accessToken === sentAccessToken) {
    accessToken = null;
  }

  const isRestored = accessToken ? true : await restoreLogin();

  if (!isRestored || !accessToken) {
    return response;
  }

  const retryHeaders = new Headers(headers);
  retryHeaders.set('Authorization', `Bearer ${accessToken}`);
  return fetch(input, { ...init, headers: retryHeaders });
};

export const logout = async (): Promise<void> => {
  const csrfToken = await requestCsrfToken();

  if (!csrfToken) {
    throw new Error('로그아웃에 실패했습니다.');
  }

  const response = await fetch(`${API_BASE_URL}/auth/logout`, {
    method: 'POST',
    credentials: 'include',
    headers: {
      'X-XSRF-TOKEN': csrfToken,
    },
  });

  if (!response.ok) {
    throw new Error('로그아웃에 실패했습니다.');
  }

  accessToken = null;
};
