import { isRecord } from '../../shared/utils';

const LOGIN_RETURN_PATH_KEY = 'harudle:login-return-path:v1';
const RETURN_PATH_BASE = 'https://harudle.invalid';
const SIMPLE_PATHS = new Set(['/', '/album', '/setting', '/diary-write']);

export const sanitizeReturnPath = (value: unknown): string => {
  if (
    typeof value !== 'string' ||
    !value.startsWith('/') ||
    value.startsWith('//') ||
    value.includes('\\')
  )
    return '/';
  try {
    const url = new URL(value, RETURN_PATH_BASE);
    if (url.origin !== RETURN_PATH_BASE) return '/';
    const allowed =
      SIMPLE_PATHS.has(url.pathname) ||
      /^\/feeds\/[0-9a-f-]+$/i.test(url.pathname) ||
      /^\/diary\/[0-9a-f-]+(?:\/feed-post)?$/i.test(url.pathname) ||
      /^\/admin(?:\/[a-zA-Z0-9/-]*)?$/.test(url.pathname);
    return allowed ? `${url.pathname}${url.search}${url.hash}` : '/';
  } catch {
    return '/';
  }
};

export const getReturnPath = (state: unknown): string =>
  sanitizeReturnPath(isRecord(state) ? state.returnTo : undefined);

export const rememberLoginReturnPath = (state: unknown): void => {
  try {
    sessionStorage.setItem(LOGIN_RETURN_PATH_KEY, getReturnPath(state));
  } catch {
    // 저장소를 사용할 수 없으면 로그인 뒤 홈으로 돌아간다.
  }
};

export const consumeLoginReturnPath = (): string => {
  try {
    const path = sessionStorage.getItem(LOGIN_RETURN_PATH_KEY);
    sessionStorage.removeItem(LOGIN_RETURN_PATH_KEY);
    return sanitizeReturnPath(path);
  } catch {
    return '/';
  }
};
