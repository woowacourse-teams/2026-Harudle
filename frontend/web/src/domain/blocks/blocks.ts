import { API_BASE_URL, isProblemDetails, RequestError } from '../../shared/api';
import { authFetch, requestCsrfToken } from '../../shared/auth';
import { isRecord } from '../../shared/utils';

export interface BlockedUser {
  readonly userId: string;
  readonly nickname: string;
  readonly profileImageUrl: string | null;
  readonly blockedAt: string;
}

export interface BlockedUsersResponse {
  readonly items: readonly BlockedUser[];
  readonly nextCursor: string | null;
  readonly hasNext: boolean;
}

export const isBlockedUsersResponse = (
  value: unknown,
): value is BlockedUsersResponse =>
  isRecord(value) &&
  Array.isArray(value.items) &&
  value.items.every(
    (item: unknown): boolean =>
      isRecord(item) &&
      typeof item.userId === 'string' &&
      item.userId.length > 0 &&
      typeof item.nickname === 'string' &&
      (item.profileImageUrl === null ||
        typeof item.profileImageUrl === 'string') &&
      typeof item.blockedAt === 'string' &&
      Number.isFinite(Date.parse(item.blockedAt)),
  ) &&
  typeof value.hasNext === 'boolean' &&
  (value.hasNext
    ? typeof value.nextCursor === 'string' && value.nextCursor.length > 0
    : value.nextCursor === null);

export const getBlockedUsers = async ({
  cursor,
  signal,
}: {
  readonly cursor?: string;
  readonly signal?: AbortSignal;
} = {}): Promise<BlockedUsersResponse> => {
  const params = new URLSearchParams({ size: '20' });
  if (cursor !== undefined) params.set('cursor', cursor);
  const response = await authFetch(`${API_BASE_URL}/blocks?${params}`, {
    signal,
  });
  const data: unknown = await response.json().catch((): null => null);
  if (!response.ok) {
    if (isProblemDetails(data)) throw new RequestError(data);
    throw new Error('차단 목록을 불러오지 못했어요. 다시 시도해주세요.');
  }
  if (!isBlockedUsersResponse(data))
    throw new Error('차단 목록 응답이 올바르지 않아요.');
  return data;
};

export const setUserBlocked = async (
  userId: string,
  blocked: boolean,
): Promise<void> => {
  const send = async (): Promise<Response> => {
    const csrfToken = await requestCsrfToken();
    return authFetch(`${API_BASE_URL}/blocks/${encodeURIComponent(userId)}`, {
      method: blocked ? 'PUT' : 'DELETE',
      headers: { 'X-XSRF-TOKEN': csrfToken },
    });
  };
  let response = await send();
  let errorData: unknown = response.ok
    ? null
    : await response.json().catch((): null => null);
  // 토큰 복원 중 CSRF 쿠키가 교체된 경우에만 저장 전 거절된 요청을 한 번 재시도한다.
  if (
    response.status === 403 &&
    isProblemDetails(errorData) &&
    errorData.status === 403 &&
    errorData.code === 'INVALID_CSRF_TOKEN'
  ) {
    response = await send();
    errorData = response.ok
      ? null
      : await response.json().catch((): null => null);
  }
  if (response.status === 204) return;
  if (isProblemDetails(errorData)) throw new RequestError(errorData);
  throw new Error(
    blocked
      ? '차단하지 못했어요. 다시 시도해주세요.'
      : '차단을 해제하지 못했어요. 다시 시도해주세요.',
  );
};
