import { API_BASE_URL, isProblemDetails, RequestError } from '../../shared/api';
import { authFetch } from '../../shared/auth';

export interface CampusLinkToken {
  readonly token: string;
  readonly redirectUri: string;
  readonly expiresAt: string;
}

const isCampusLinkToken = (value: unknown): value is CampusLinkToken => {
  if (typeof value !== 'object' || value === null) return false;
  const { token, redirectUri, expiresAt } = value as Record<string, unknown>;
  return (
    typeof token === 'string' &&
    typeof redirectUri === 'string' &&
    typeof expiresAt === 'string'
  );
};

export const issueCampusLinkToken = async (
  state: string,
): Promise<CampusLinkToken> => {
  const response = await authFetch(`${API_BASE_URL}/campus/link-tokens`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ state }),
  });
  const data: unknown = await response.json().catch(() => null);
  if (!response.ok) {
    if (isProblemDetails(data)) throw new RequestError(data);
    throw new Error('campus_link_failed');
  }
  if (!isCampusLinkToken(data)) throw new Error('campus_link_failed');
  return data;
};

// 하루들이 정한 캠퍼스 주소에 token과 state만 붙인다.
export const campusCallbackUrl = (
  { token, redirectUri }: CampusLinkToken,
  state: string,
): string => {
  const url = new URL(redirectUri);
  url.searchParams.set('token', token);
  url.searchParams.set('state', state);
  return url.toString();
};

// 뒤로 가기로 토큰이 다시 쓰이지 않게 기록을 남기지 않고 이동한다.
export const openCampus = (url: string): void => {
  window.location.replace(url);
};

// 로그인 · 약관 동의는 새 탭에서 한다. 기존 로그인 흐름을 바꾸지 않고, 이 탭은 그대로 기다린다.
// noopener를 넘기면 성공해도 null이 돌아와 막힘 여부를 알 수 없으므로, 연 뒤 opener를 끊는다.
export const openInNewTab = (path: string): boolean => {
  const opened = window.open(path, '_blank');
  if (opened === null) return false;
  opened.opener = null;
  return true;
};
