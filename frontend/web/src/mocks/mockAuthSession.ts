const LOGGED_OUT_KEY = 'harudle:mock:logged-out:v1';
export const MOCK_CSRF_TOKEN = 'mock-csrf-token';

export const isMockSessionLoggedIn = (): boolean => {
  try {
    return sessionStorage.getItem(LOGGED_OUT_KEY) === 'false';
  } catch {
    return false;
  }
};

export const markMockSessionLoggedIn = (): void => {
  try {
    sessionStorage.setItem(LOGGED_OUT_KEY, 'false');
  } catch {
    /* 테스트 저장소가 없으면 기존 기본 응답을 유지한다. */
  }
};

export const isMockSessionLoggedOut = (): boolean => {
  try {
    return sessionStorage.getItem(LOGGED_OUT_KEY) === 'true';
  } catch {
    return false;
  }
};

export const markMockSessionLoggedOut = (): void => {
  try {
    sessionStorage.setItem(LOGGED_OUT_KEY, 'true');
  } catch {
    /* 테스트 저장소가 없으면 기존 기본 응답을 유지한다. */
  }
};

export const resetMockSession = (): void => {
  try {
    sessionStorage.removeItem(LOGGED_OUT_KEY);
  } catch {
    /* 테스트 저장소가 없으면 기존 기본 응답을 유지한다. */
  }
};
