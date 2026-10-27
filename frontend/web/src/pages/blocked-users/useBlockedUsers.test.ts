import { beforeEach, describe, expect, it, jest } from '@jest/globals';
import { act, renderHook, waitFor } from '@testing-library/react';
import { StrictMode } from 'react';
import { getBlockedUsers } from '../../domain/blocks/blocks';
import useBlockedUsers from './useBlockedUsers';

jest.mock('../../domain/blocks/blocks', () => ({
  getBlockedUsers: jest.fn(),
}));

const user = {
  userId: 'user-1',
  nickname: '네오',
  profileImageUrl: null,
  blockedAt: '2026-10-22T00:00:00Z',
};
const remaining = { ...user, userId: 'user-2', nickname: '하루' };

beforeEach((): void => {
  jest.mocked(getBlockedUsers).mockReset();
});

describe('차단 목록 조회', (): void => {
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
    expect(result.current.isLoading).toBe(true);
    expect(signal?.aborted).toBe(false);

    unmount();
    expect(signal?.aborted).toBe(true);
    await act(async (): Promise<void> => {
      finishRead({ items: [user], nextCursor: null, hasNext: false });
    });
    expect(result.current.request.status).toBe('loading');
  });

  it.each(['성공', '실패'] as const)(
    'StrictMode에서 취소된 이전 조회가 늦게 %s해도 현재 조회의 진행 상태와 중복 방어를 유지한다',
    async (previousResult): Promise<void> => {
      let finishPrevious: (
        data: Awaited<ReturnType<typeof getBlockedUsers>>,
      ) => void = (): void => undefined;
      let failPrevious: (error: Error) => void = (): void => undefined;
      let finishCurrent: (
        data: Awaited<ReturnType<typeof getBlockedUsers>>,
      ) => void = (): void => undefined;
      jest
        .mocked(getBlockedUsers)
        .mockImplementationOnce(
          (): ReturnType<typeof getBlockedUsers> =>
            new Promise((resolve, reject): void => {
              finishPrevious = resolve;
              failPrevious = reject;
            }),
        )
        .mockImplementationOnce(
          (): ReturnType<typeof getBlockedUsers> =>
            new Promise((resolve): void => {
              finishCurrent = resolve;
            }),
        );
      const { result } = renderHook(useBlockedUsers, { wrapper: StrictMode });
      expect(getBlockedUsers).toHaveBeenCalledTimes(2);
      const previousSignal =
        jest.mocked(getBlockedUsers).mock.calls[0][0]?.signal;
      const currentSignal =
        jest.mocked(getBlockedUsers).mock.calls[1][0]?.signal;
      expect(previousSignal?.aborted).toBe(true);
      expect(currentSignal?.aborted).toBe(false);

      await act(async (): Promise<void> => {
        if (previousResult === '성공') {
          finishPrevious({ items: [user], nextCursor: null, hasNext: false });
        } else {
          failPrevious(new Error('취소된 이전 조회 실패'));
        }
      });
      expect(result.current.request.status).toBe('loading');
      expect(result.current.isLoading).toBe(true);
      expect(result.current.listRetry).toBeNull();
      await act(async (): Promise<void> => {
        await result.current.refresh();
        await result.current.retry();
      });
      expect(getBlockedUsers).toHaveBeenCalledTimes(2);
      expect(result.current.isLoading).toBe(true);

      await act(async (): Promise<void> => {
        finishCurrent({ items: [remaining], nextCursor: null, hasNext: false });
      });
      expect(result.current.request).toEqual({
        status: 'success',
        data: { items: [remaining], nextCursor: null, hasNext: false },
      });
      expect(result.current.isLoading).toBe(false);
      expect(result.current.listRetry).toBeNull();
    },
  );

  it('같은 순간의 추가 조회는 한 번만 실행하고 기존 사용자와 겹치는 행은 합치지 않는다', async (): Promise<void> => {
    let finishRead: (
      data: Awaited<ReturnType<typeof getBlockedUsers>>,
    ) => void = (): void => undefined;
    jest
      .mocked(getBlockedUsers)
      .mockResolvedValueOnce({
        items: [user],
        nextCursor: 'next-page',
        hasNext: true,
      })
      .mockImplementationOnce(
        (): ReturnType<typeof getBlockedUsers> =>
          new Promise((resolve): void => {
            finishRead = resolve;
          }),
      );
    const { result } = renderHook(useBlockedUsers);
    await waitFor((): void =>
      expect(result.current.request.status).toBe('success'),
    );

    act((): void => {
      void result.current.loadMore();
      void result.current.loadMore();
      void result.current.refresh();
    });
    expect(getBlockedUsers).toHaveBeenCalledTimes(2);
    expect(result.current.isLoading).toBe(true);
    expect(getBlockedUsers).toHaveBeenLastCalledWith({
      cursor: 'next-page',
      signal: expect.any(AbortSignal),
    });
    await act(async (): Promise<void> => {
      finishRead({
        items: [user, remaining],
        nextCursor: null,
        hasNext: false,
      });
    });
    expect(result.current.request).toEqual({
      status: 'success',
      data: { items: [user, remaining], nextCursor: null, hasNext: false },
    });
    expect(result.current.isLoading).toBe(false);
    expect(result.current.listRetry).toBeNull();
  });

  it('추가 조회 실패는 기존 목록과 실패한 커서를 보존하고 같은 커서로 재시도한다', async (): Promise<void> => {
    jest
      .mocked(getBlockedUsers)
      .mockResolvedValueOnce({
        items: [user],
        nextCursor: 'next-page',
        hasNext: true,
      })
      .mockRejectedValueOnce(new Error('추가 조회 실패'))
      .mockResolvedValueOnce({
        items: [remaining],
        nextCursor: null,
        hasNext: false,
      });
    const { result } = renderHook(useBlockedUsers);
    await waitFor((): void =>
      expect(result.current.request.status).toBe('success'),
    );
    await act(async (): Promise<void> => result.current.loadMore());
    expect(result.current.listRetry).toEqual({
      kind: 'more',
      cursor: 'next-page',
    });
    expect(result.current.request).toMatchObject({
      status: 'success',
      data: { items: [user], nextCursor: 'next-page', hasNext: true },
    });
    await act(async (): Promise<void> => result.current.retry());
    expect(getBlockedUsers).toHaveBeenNthCalledWith(3, {
      cursor: 'next-page',
      signal: expect.any(AbortSignal),
    });
    expect(result.current.request).toMatchObject({
      status: 'success',
      data: { items: [user, remaining] },
    });
    expect(result.current.listRetry).toBeNull();
  });

  it('호출부에서 제거한 행은 목록 갱신 실패에도 복구하지 않고 첫 페이지 조회만 재시도한다', async (): Promise<void> => {
    jest
      .mocked(getBlockedUsers)
      .mockResolvedValueOnce({
        items: [user, remaining],
        nextCursor: 'old-page',
        hasNext: true,
      })
      .mockRejectedValueOnce(new Error('갱신 실패'))
      .mockResolvedValueOnce({
        items: [remaining],
        nextCursor: null,
        hasNext: false,
      });
    const { result } = renderHook(useBlockedUsers);
    await waitFor((): void =>
      expect(result.current.request.status).toBe('success'),
    );
    act((): void => result.current.removeUser(user.userId));
    await act(async (): Promise<void> => result.current.refresh());
    expect(result.current.request).toMatchObject({
      status: 'success',
      data: { items: [remaining] },
    });
    expect(result.current.listRetry).toEqual({ kind: 'refresh' });
    expect(result.current.isLoading).toBe(false);

    await act(async (): Promise<void> => result.current.retry());
    expect(getBlockedUsers).toHaveBeenNthCalledWith(3, {
      cursor: undefined,
      signal: expect.any(AbortSignal),
    });
    expect(result.current.request).toEqual({
      status: 'success',
      data: { items: [remaining], nextCursor: null, hasNext: false },
    });
    expect(result.current.listRetry).toBeNull();
  });
});
