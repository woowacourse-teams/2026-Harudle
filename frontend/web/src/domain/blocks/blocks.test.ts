/** @jest-environment node */
import { beforeEach, describe, expect, it, jest } from '@jest/globals';
import {
  getBlockedUsers,
  isBlockedUsersResponse,
  setUserBlocked,
} from './blocks';
import { authFetch, requestCsrfToken } from '../../shared/auth';

jest.mock('../../shared/auth', () => ({
  authFetch: jest.fn(),
  requestCsrfToken: jest.fn(),
}));
const user = {
  userId: 'user-1',
  nickname: '탈퇴한 사용자',
  profileImageUrl: null,
  blockedAt: '2026-10-22T00:00:00Z',
};
const problem = (code: string, status = 403): Response =>
  Response.json(
    {
      type: 'urn:problem',
      title: code,
      detail: code,
      instance: '/api/v1/blocks/user-1',
      code,
      status,
    },
    { status },
  );
beforeEach((): void => {
  jest.mocked(authFetch).mockReset();
  jest.mocked(requestCsrfToken).mockReset().mockResolvedValue('csrf-1');
});
describe('차단 API', (): void => {
  it('목록을 인증 조회하고 커서를 안전하게 전달한다', async (): Promise<void> => {
    const page = { items: [user], hasNext: false, nextCursor: null };
    jest.mocked(authFetch).mockResolvedValue(Response.json(page));
    await expect(getBlockedUsers({ cursor: 'a+b&c' })).resolves.toEqual(page);
    expect(authFetch).toHaveBeenCalledWith(
      '/api/v1/blocks?size=20&cursor=a%2Bb%26c',
      { signal: undefined },
    );
  });
  it.each([true, false])(
    '차단=%s 요청은204로 완료한다',
    async (blocked): Promise<void> => {
      jest
        .mocked(authFetch)
        .mockResolvedValue(new Response(null, { status: 204 }));
      await expect(setUserBlocked('user-1', blocked)).resolves.toBeUndefined();
      expect(authFetch).toHaveBeenCalledWith('/api/v1/blocks/user-1', {
        method: blocked ? 'PUT' : 'DELETE',
        headers: { 'X-XSRF-TOKEN': 'csrf-1' },
      });
    },
  );
  it('확정된 CSRF403만 새 토큰으로 한 번 재시도한다', async (): Promise<void> => {
    jest
      .mocked(requestCsrfToken)
      .mockResolvedValueOnce('csrf-1')
      .mockResolvedValueOnce('csrf-2');
    jest
      .mocked(authFetch)
      .mockResolvedValueOnce(problem('INVALID_CSRF_TOKEN'))
      .mockResolvedValueOnce(new Response(null, { status: 204 }));
    await setUserBlocked('user-1', true);
    expect(authFetch).toHaveBeenCalledTimes(2);
    expect(authFetch).toHaveBeenLastCalledWith('/api/v1/blocks/user-1', {
      method: 'PUT',
      headers: { 'X-XSRF-TOKEN': 'csrf-2' },
    });
  });
  it.each([
    ['FORBIDDEN', 403],
    ['UNAUTHORIZED', 401],
    ['BLOCK_UNAVAILABLE', 503],
    ['INVALID_CSRF_TOKEN', 400],
  ])(
    '다른 오류 %s/%s는 재시도하지 않는다',
    async (code, status): Promise<void> => {
      jest.mocked(authFetch).mockResolvedValue(problem(code, status));
      await expect(setUserBlocked('user-1', true)).rejects.toThrow(code);
      expect(authFetch).toHaveBeenCalledTimes(1);
    },
  );
  it('두번째 CSRF 거절은 오류로 전달한다', async (): Promise<void> => {
    jest
      .mocked(authFetch)
      .mockImplementation(async (): Promise<Response> =>
        problem('INVALID_CSRF_TOKEN'),
      );
    await expect(setUserBlocked('user-1', false)).rejects.toThrow(
      'INVALID_CSRF_TOKEN',
    );
    expect(authFetch).toHaveBeenCalledTimes(2);
  });
  it('204가 아닌 성공 응답도 계약 오류로 처리한다', async (): Promise<void> => {
    jest.mocked(authFetch).mockResolvedValue(Response.json({}));
    await expect(setUserBlocked('user-1', true)).rejects.toThrow(
      '차단하지 못했어요',
    );
  });
});
describe('차단 목록 경계 검증', (): void => {
  it('탈퇴 프로필과 페이지 연속성을 허용한다', (): void => {
    expect(
      isBlockedUsersResponse({
        items: [user],
        nextCursor: 'opaque',
        hasNext: true,
      }),
    ).toBe(true);
    expect(
      isBlockedUsersResponse({ items: [], nextCursor: null, hasNext: false }),
    ).toBe(true);
  });
  it.each([
    null,
    { items: [user], hasNext: true, nextCursor: null },
    { items: [user], hasNext: false, nextCursor: 'cursor' },
    {
      items: [{ ...user, blockedAt: 'bad' }],
      hasNext: false,
      nextCursor: null,
    },
    {
      items: [{ ...user, profileImageUrl: 4 }],
      hasNext: false,
      nextCursor: null,
    },
    { items: [{ ...user, userId: '' }], hasNext: false, nextCursor: null },
  ])('잘못된 구조를 거부한다 %j', (value): void => {
    expect(isBlockedUsersResponse(value)).toBe(false);
  });
});
