// 로그인 뒤 돌아갈 경로. OAuth는 같은 탭에서 오가므로 sessionStorage에 둔다.
// 열린 리다이렉트를 막으려고 허용한 경로(우테코 캠퍼스 연결)만 저장하고 꺼낸다.
const POST_LOGIN_PATH_KEY = 'harudle.post-login-path';
const ALLOWED_PATH = /^\/campus\/connect\?state=[A-Za-z0-9_-]{43,128}$/;

export const isAllowedPostLoginPath = (path: string): boolean =>
  ALLOWED_PATH.test(path);

export const savePostLoginPath = (path: string): void => {
  if (!isAllowedPostLoginPath(path)) return;
  try {
    sessionStorage.setItem(POST_LOGIN_PATH_KEY, path);
  } catch {
    // 저장하지 못하면 로그인 뒤 홈으로 간다.
  }
};

export const peekPostLoginPath = (): string | null => {
  try {
    const path = sessionStorage.getItem(POST_LOGIN_PATH_KEY);
    return path !== null && isAllowedPostLoginPath(path) ? path : null;
  } catch {
    return null;
  }
};

export const clearPostLoginPath = (): void => {
  try {
    sessionStorage.removeItem(POST_LOGIN_PATH_KEY);
  } catch {
    // 지우지 못해도 허용 경로만 꺼내므로 안전하다.
  }
};
