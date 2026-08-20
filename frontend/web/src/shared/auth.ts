import { API_BASE_URL, isProblemDetails, RequestError } from './api';

let accessToken: string | null = null;
let refreshRequest: Promise<void> | null = null; // Single-Flight 패턴

export const setAccessToken = (token: string | null): void => {
  accessToken = token;
};

// 1. fetch호출 자체를 매개변수로 받는다.
// 2. option만 받고, fetch를 호출한다.
// 2번으로 하게 되면 authFetch 자체를 fetch와 똑같이 쓸 수 있음

type AuthFetchRequestInfo = Parameters<typeof fetch>[0];
type AuthFetchRequestInit = Parameters<typeof fetch>[1];

export const authFetch = async (
  info: AuthFetchRequestInfo,
  init?: AuthFetchRequestInit,
) => {
  try {
    if (!accessToken) {
      await restoreAccessToken();
    }

    if (!accessToken) {
      throw new Error('Access Token 복구 실패');
    }

    const usedToken = accessToken; // 액세스 토큰 race condition 문제 해결을 위해 복사

    // 여기부터는 액세스 토큰이 어떻게든 있는 상태이다.

    // 2. API 요청
    let response = await fetchWithAccessToken(info, init, usedToken);

    // 3. 401이 아니면 그대로 끝내고 외부로 성공 및 에러 처리 책임을 넘긴다.
    if (response.status !== 401) {
      return response;
    }

    // 4. 401이면 기존 토큰 폐기 후 재발급한다.
    // usedToken과 accessToken이 다르다는 것은 다른 api 요청이 이미 액세스 토큰을 갱신한 상황이다.

    // 내가 사용했던 토큰이 아직 현재 토큰이면
    // 내가 처음 401을 발견한 것이므로 폐기한다.
    if (accessToken === usedToken) {
      setAccessToken(null);
    }

    // 현재 토큰이 없다면 누군가 refresh 중이거나,
    // 내가 refresh를 해야 하는 상황이다.
    if (!accessToken) {
      await restoreAccessToken();
    }
    // 5. 딱 한 번 재요청
    response = await fetchWithAccessToken(info, init, accessToken);
    return response;
  } catch (error: unknown) {
    // refresh 자체가 실패한 경우에만 여기서 처리한다.
    if (
      error instanceof RequestError &&
      error.problem.code === 'INVALID_REFRESH_TOKEN'
    ) {
      alert('세션이 만료되었습니다. 다시 로그인 해주세요.');
      window.location.href = '/login';
    }

    throw error; // 인증 에러가 아니면, authFetch를 사용하는 곳에서 에러처리 책임을 넘긴다.
  }
};

// 액세스 토큰 없으면 리프레시 토큰 받고,
// 성공하면, 그냥 성공하는거고, 실패하면 refresh 시도 후 refresh도 실패하면 navigate가 맞음

// 여기서 뭘 return 해줘야 사용하는 곳에서 편하게 쓸까?
// 아마 이 내부에서 try/catch를 하는 것은 별로 좋지 않다.
const fetchWithAccessToken = (
  info: AuthFetchRequestInfo,
  init: AuthFetchRequestInit | undefined,
  accessToken: string,
): Promise<Response> => {
  const headers = new Headers(init?.headers);
  headers.set('Authorization', `Bearer ${accessToken}`);

  return fetch(info, {
    ...init,
    headers,
  });
};

export interface RefreshTokenResponse {
  accessToken: string;
  tokenType: 'Bearer';
  expiresIn: number;
}

export const isRefreshTokenResponse = (
  value: unknown,
): value is RefreshTokenResponse => {
  return (
    typeof value === 'object' &&
    value !== null &&
    'accessToken' in value &&
    typeof value.accessToken === 'string' &&
    'tokenType' in value &&
    value.tokenType === 'Bearer' &&
    'expiresIn' in value &&
    typeof value.expiresIn === 'number'
  );
};

const restoreAccessToken = async (): Promise<void> => {
  if (refreshRequest) {
    await refreshRequest;
    return;
  }

  // 아무도 작업중이 아니라면 새토큰 발급 요청 실시
  // 성공하든 실패하든 작업이 끝났기 때문에 finally에서 내 작업이 끝났음을 알린다.
  refreshRequest = requestNewAccessToken().finally(
    () => (refreshRequest = null),
  );

  // 리프레시 요청을 날린 본인도 해당 요청을 기다려야 한다.
  // 기다리지 않으면 액세스 토큰이 오지도 않았는데 원래 API 재요청을 할 수 있기 때문이다.
  await refreshRequest;
};

const requestNewAccessToken = async (): Promise<void> => {
  const csrfToken = await requestCsrfToken();
  const response = await fetch(`${API_BASE_URL}/auth/refresh`, {
    method: 'POST',
    credentials: 'include',
    headers: {
      'X-XSRF-TOKEN': csrfToken,
    },
  });

  if (!response.ok) {
    const errorData = await response.json();
    if (isProblemDetails(errorData)) {
      throw new RequestError(errorData);
    }

    throw new Error('알 수 없는 에러가 발생했습니다.');
  }

  const data: unknown = await response.json();
  if (!isRefreshTokenResponse(data)) {
    throw new Error('RefreshToken 응답 형식이 일치하지 않습니다.');
  }

  setAccessToken(data.accessToken);
};

export interface CsrfTokenResponse {
  token: string;
}

export const isCsrfTokenResponse = (
  value: unknown,
): value is CsrfTokenResponse => {
  return (
    typeof value === 'object' &&
    value !== null &&
    'token' in value &&
    typeof value.token === 'string'
  );
};

export const requestCsrfToken = async (): Promise<string> => {
  const response = await fetch(`${API_BASE_URL}/auth/csrf`, {
    credentials: 'include',
  });

  if (!response.ok) {
    const errorData: unknown = await response.json();

    if (isProblemDetails(errorData)) {
      throw new RequestError(errorData);
    }

    throw new Error('CSRF Token 발급에 실패했습니다.');
  }

  const data: unknown = await response.json();

  if (!isCsrfTokenResponse(data)) {
    throw new Error('CSRF Token 응답 형식이 일치하지 않습니다.');
  }

  return data.token;
};
