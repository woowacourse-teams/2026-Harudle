import { API_BASE_URL, isProblemDetails, RequestError } from '../../shared/api';
import { authFetch } from '../../shared/auth';

export interface CampusLinkCode {
  readonly code: string;
  readonly redirectUri: string;
  readonly expiresAt: string;
}

const isCampusLinkCode = (value: unknown): value is CampusLinkCode => {
  if (typeof value !== 'object' || value === null) return false;
  const { code, redirectUri, expiresAt } = value as Record<string, unknown>;
  return (
    typeof code === 'string' &&
    typeof redirectUri === 'string' &&
    typeof expiresAt === 'string'
  );
};

export const issueCampusLinkCode = async (
  state: string,
): Promise<CampusLinkCode> => {
  const response = await authFetch(`${API_BASE_URL}/campus/link-codes`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ state }),
  });
  const data: unknown = await response.json().catch(() => null);
  if (!response.ok) {
    if (isProblemDetails(data)) throw new RequestError(data);
    throw new Error('campus_link_failed');
  }
  if (!isCampusLinkCode(data)) throw new Error('campus_link_failed');
  return data;
};

// 하루들이 정한 캠퍼스 주소에 code와 state만 붙인다.
export const campusCallbackUrl = (
  { code, redirectUri }: CampusLinkCode,
  state: string,
): string => {
  const url = new URL(redirectUri);
  url.searchParams.set('code', code);
  url.searchParams.set('state', state);
  return url.toString();
};

// 뒤로 가기로 코드가 다시 쓰이지 않게 기록을 남기지 않고 이동한다.
export const openCampus = (url: string): void => {
  window.location.replace(url);
};
