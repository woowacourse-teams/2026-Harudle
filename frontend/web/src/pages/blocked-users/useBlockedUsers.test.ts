import { beforeEach, describe, expect, it, jest } from '@jest/globals';
import { act, renderHook, waitFor } from '@testing-library/react';
import { getBlockedUsers, setUserBlocked } from '../../domain/blocks/blocks';
import { setFeedListCache } from '../../domain/feeds/feedListCache';
import useBlockedUsers from './useBlockedUsers';

const mockAddToast = jest.fn();

jest.mock('../../domain/blocks/blocks', () => ({
  getBlockedUsers: jest.fn(),
  setUserBlocked: jest.fn(),
}));
jest.mock('../../domain/feeds/feedListCache', () => ({
  setFeedListCache: jest.fn(),
}));
jest.mock('../../shared/ToastContext', () => ({
  useToastContext: (): { readonly addToast: typeof mockAddToast } => ({
    addToast: mockAddToast,
  }),
}));

const user = {
  userId: 'user-1',
  nickname: '네오',
  profileImageUrl: null,
  blockedAt: '2026-10-22T00:00:00Z',
};

beforeEach((): void => {
  jest.mocked(getBlockedUsers).mockReset();
  jest.mocked(setUserBlocked).mockReset();
  jest.mocked(setFeedListCache).mockClear();
  mockAddToast.mockClear();
});

describe('차단 목록의 요청 수명', (): void => {
  it('첫 조회 도중 화면을 떠나면 요청을 취소하고 늦은 응답을 반영하지 않는다', async (): Promise<void> => {
    let finishRead: (
      data: Awaited<ReturnType<typeof getBlockedUsers>>,
    ) => void = (): void => undefined;
    jest.mocked(getBlockedUsers).mockImplementationOnce(
      (): ReturnType<typeof getBlockedUsers> =>
        new Promise((resolve): void => {
          finishRead = resolve;
        }),
    );
    const { result, unmount } = renderHook(useBlockedUsers);
    const signal = jest.mocked(getBlockedUsers).mock.calls[0][0]?.signal;
    expect(result.current.request.status).toBe('loading');
    expect(signal?.aborted).toBe(false);

    unmount();
    expect(signal?.aborted).toBe(true);
    await act(async (): Promise<void> => {
      finishRead({ items: [user], nextCursor: null, hasNext: false });
    });

    expect(result.current.request.status).toBe('loading');
    expect(mockAddToast).not.toHaveBeenCalled();
    expect(setFeedListCache).not.toHaveBeenCalled();
  });

  it('같은 순간의 해제와 추가 조회는 한 번만 처리하고 실패 후에는 다시 해제할 수 있다', async (): Promise<void> => {
    let failUnblock: (error: Error) => void = (): void => undefined;
    jest.mocked(getBlockedUsers).mockResolvedValueOnce({
      items: [user],
      nextCursor: 'next-page',
      hasNext: true,
    });
    jest.mocked(setUserBlocked).mockImplementationOnce(
      (): Promise<void> =>
        new Promise((_resolve, reject): void => {
          failUnblock = reject;
        }),
    );
    const { result } = renderHook(useBlockedUsers);
    await waitFor((): void =>
      expect(result.current.request.status).toBe('success'),
    );

    act((): void => {
      void result.current.unblock(user);
      void result.current.unblock(user);
      void result.current.loadMore();
    });
    expect(setUserBlocked).toHaveBeenCalledTimes(1);
    expect(getBlockedUsers).toHaveBeenCalledTimes(1);
    await act(async (): Promise<void> => {
      failUnblock(new Error('해제 실패'));
    });
    expect(result.current.busy).toBeNull();
    expect(result.current.request).toEqual({
      status: 'success',
      data: { items: [user], nextCursor: 'next-page', hasNext: true },
    });
    expect(mockAddToast).toHaveBeenCalledWith(
      {
        type: 'error',
        message: '차단을 해제하지 못했어요. 다시 시도해 주세요.',
      },
      2000,
    );

    jest.mocked(setUserBlocked).mockResolvedValueOnce(undefined);
    jest.mocked(getBlockedUsers).mockResolvedValueOnce({
      items: [],
      nextCursor: null,
      hasNext: false,
    });
    await act(async (): Promise<void> => {
      await result.current.unblock(user);
    });

    expect(setUserBlocked).toHaveBeenCalledTimes(2);
    expect(getBlockedUsers).toHaveBeenCalledTimes(2);
    expect(setFeedListCache).toHaveBeenCalledWith(null);
    expect(result.current.request).toEqual({
      status: 'success',
      data: { items: [], nextCursor: null, hasNext: false },
    });
  });

  it('화면을 떠난 뒤 도착한 해제 실패는 토스트나 후속 조회를 만들지 않는다', async (): Promise<void> => {
    let failUnblock: (error: Error) => void = (): void => undefined;
    jest.mocked(getBlockedUsers).mockResolvedValueOnce({
      items: [user],
      nextCursor: null,
      hasNext: false,
    });
    jest.mocked(setUserBlocked).mockImplementationOnce(
      (): Promise<void> =>
        new Promise((_resolve, reject): void => {
          failUnblock = reject;
        }),
    );
    const { result, unmount } = renderHook(useBlockedUsers);
    await waitFor((): void =>
      expect(result.current.request.status).toBe('success'),
    );
    act((): void => {
      void result.current.unblock(user);
    });
    unmount();
    await act(async (): Promise<void> => {
      failUnblock(new Error('해제 실패'));
    });

    expect(mockAddToast).not.toHaveBeenCalled();
    expect(setFeedListCache).not.toHaveBeenCalled();
    expect(getBlockedUsers).toHaveBeenCalledTimes(1);
  });
});
