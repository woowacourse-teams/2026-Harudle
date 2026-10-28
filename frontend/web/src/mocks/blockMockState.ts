import {
  isBlockedUsersResponse,
  type BlockedUser,
} from '../domain/blocks/blocks';

const STORAGE_KEY = 'harudle.mock-blocked-users';
// 실제 서버의 차단 상태를 새로고침 후에도 재현하는 개발용 저장소다.
export const readMockBlocks = (): readonly BlockedUser[] => {
  try {
    const raw = sessionStorage.getItem(STORAGE_KEY);
    const items: unknown = raw ? JSON.parse(raw) : [];
    const page: unknown = { items, nextCursor: null, hasNext: false };
    return isBlockedUsersResponse(page) ? page.items : [];
  } catch {
    return [];
  }
};
export const writeMockBlocks = (items: readonly BlockedUser[]): void => {
  sessionStorage.setItem(STORAGE_KEY, JSON.stringify(items));
};
export const isMockAuthorBlocked = (
  request: Request,
  authorId: string,
): boolean =>
  request.headers.get('Authorization') === 'Bearer mock-access-token' &&
  readMockBlocks().some((user): boolean => user.userId === authorId);
