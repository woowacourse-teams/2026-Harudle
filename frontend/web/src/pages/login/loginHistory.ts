export type LoginProvider = 'kakao' | 'google' | 'apple';

export const LOGIN_PROVIDER_LABELS = {
  kakao: '카카오',
  google: 'Google',
  apple: 'Apple',
} as const;

const LAST_LOGIN_KEY = 'harudle.last-login-provider';
const PENDING_LOGIN_KEY = 'harudle.pending-login-provider';

const isLoginProvider = (value: unknown): value is LoginProvider =>
  value === 'kakao' || value === 'google' || value === 'apple';

const readProvider = (
  storage: 'localStorage' | 'sessionStorage',
  key: string,
): LoginProvider | null => {
  try {
    const value: unknown = window[storage].getItem(key);
    return isLoginProvider(value) ? value : null;
  } catch {
    return null;
  }
};

export const getLastLoginProvider = (): LoginProvider | null =>
  readProvider('localStorage', LAST_LOGIN_KEY);

export const getPendingLoginProvider = (): LoginProvider | null =>
  readProvider('sessionStorage', PENDING_LOGIN_KEY);

export const setPendingLoginProvider = (provider: LoginProvider): void => {
  try {
    sessionStorage.setItem(PENDING_LOGIN_KEY, provider);
  } catch {
    // 로그인 방법을 기억하지 못해도 인증은 진행한다.
  }
};

export const clearPendingLoginProvider = (): void => {
  try {
    sessionStorage.removeItem(PENDING_LOGIN_KEY);
  } catch {
    // 저장소 사용이 제한돼도 로그인 화면을 표시한다.
  }
};

export const rememberSuccessfulLogin = (
  provider: LoginProvider | null,
): void => {
  try {
    localStorage.setItem('harudle.has-ever-logged-in', 'true');
    localStorage.setItem('harudle.has-completed-oauth', 'true');
    if (provider !== null) localStorage.setItem(LAST_LOGIN_KEY, provider);
  } catch {
    // 로그인 이력 저장 실패가 인증 성공을 실패로 바꾸지 않게 한다.
  }
  clearPendingLoginProvider();
};
