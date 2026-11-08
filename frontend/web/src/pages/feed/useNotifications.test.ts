import { beforeEach, describe, expect, it, jest } from '@jest/globals';
import { act, renderHook, waitFor } from '@testing-library/react';
import {
  getNotifications,
  markNotificationsRead,
  type NotificationsResponse,
} from '../../domain/notifications/notifications';
import useNotifications from './useNotifications';

jest.mock('../../domain/notifications/notifications', () => ({
  getNotifications: jest.fn(),
  markNotificationsRead: jest.fn(),
}));

const item = {
  id: 'notification-1',
  type: 'COMMENT',
  feedId: 'feed-1',
  actor: null,
  message: '탈퇴한 회원이 댓글을 남겼습니다.',
  createdAt: '2026-10-29T00:00:00Z',
  read: false,
} as const;
const page: NotificationsResponse = {
  items: [item],
  nextCursor: null,
  hasNext: false,
  unreadCount: 1,
};

beforeEach((): void => {
  jest.mocked(getNotifications).mockReset().mockResolvedValue(page);
  jest.mocked(markNotificationsRead).mockReset().mockResolvedValue([item.id]);
});

describe('알림 목록과 읽음 처리', (): void => {
  it('재조회 중에도 기존 목록을 유지하고 성공하면 교체하며 실패하면 그대로 둔다', async (): Promise<void> => {
    const { result } = renderHook(useNotifications);
    await waitFor((): void =>
      expect(result.current.request.status).toBe('success'),
    );
    let finish: (data: NotificationsResponse) => void = (): void => undefined;
    jest.mocked(getNotifications).mockImplementationOnce(
      (): Promise<NotificationsResponse> =>
        new Promise((resolve): void => {
          finish = resolve;
        }),
    );
    let refreshing: Promise<NotificationsResponse | null> =
      Promise.resolve(null);
    act((): void => {
      refreshing = result.current.execute();
    });
    expect(result.current.request).toEqual({ status: 'success', data: page });
    const updated = { ...page, items: [{ ...item, message: '새 알림 문구' }] };
    await act(async (): Promise<void> => {
      finish(updated);
      await refreshing;
    });
    expect(result.current.request).toEqual({
      status: 'success',
      data: updated,
    });
    jest
      .mocked(getNotifications)
      .mockRejectedValueOnce(new Error('재조회 실패'));
    await act(async (): Promise<void> => {
      await expect(result.current.execute()).resolves.toBeNull();
    });
    expect(result.current.request).toEqual({
      status: 'success',
      data: updated,
    });
  });

  it('최초 조회는 알림을 읽지 않고 읽음 성공 후 같은 목록의 미읽음 수만 갱신한다', async (): Promise<void> => {
    const { result } = renderHook(useNotifications);
    await waitFor((): void =>
      expect(result.current.request.status).toBe('success'),
    );
    expect(markNotificationsRead).not.toHaveBeenCalled();
    await act(async (): Promise<void> => {
      await result.current.markRead(page.items);
    });
    expect(result.current.request).toMatchObject({
      status: 'success',
      data: { unreadCount: 0, items: [{ read: true }] },
    });
    expect(getNotifications).toHaveBeenCalledTimes(1);
  });

  it('읽음 처리가 끝나기 전에도 다음 페이지를 불러온다', async (): Promise<void> => {
    jest.mocked(getNotifications).mockResolvedValueOnce({
      ...page,
      hasNext: true,
      nextCursor: 'next',
      unreadCount: 2,
    });
    const { result } = renderHook(useNotifications);
    await waitFor((): void =>
      expect(result.current.request.status).toBe('success'),
    );
    let finish: (ids: readonly string[]) => void = (): void => undefined;
    jest.mocked(markNotificationsRead).mockImplementationOnce(
      (): Promise<readonly string[]> =>
        new Promise((resolve): void => {
          finish = resolve;
        }),
    );
    let reading = Promise.resolve();
    act((): void => {
      reading = result.current.markRead(page.items);
    });
    jest
      .mocked(getNotifications)
      .mockResolvedValueOnce({ ...page, items: [{ ...item, id: 'second' }] });
    await act(async (): Promise<void> => {
      await result.current.loadMore();
    });
    expect(result.current.loadMoreRequest.status).toBe('success');
    expect(result.current.request).toMatchObject({
      data: { items: [{ id: item.id }, { id: 'second' }], hasNext: false },
    });
    await act(async (): Promise<void> => {
      finish([item.id]);
      await reading;
    });
    expect(result.current.request).toMatchObject({ data: { unreadCount: 1 } });
  });

  it('추가 조회가 실패하면 기존 목록과 커서를 유지하고 같은 페이지를 재시도한다', async (): Promise<void> => {
    jest
      .mocked(getNotifications)
      .mockResolvedValueOnce({ ...page, hasNext: true, nextCursor: 'next' });
    const { result } = renderHook(useNotifications);
    await waitFor((): void =>
      expect(result.current.request.status).toBe('success'),
    );
    jest
      .mocked(getNotifications)
      .mockRejectedValueOnce(new Error('추가 조회 실패'));
    await act(async (): Promise<void> => {
      await result.current.loadMore();
    });
    expect(result.current.loadMoreRequest.status).toBe('error');
    expect(result.current.request).toMatchObject({
      data: { items: [{ id: item.id }], nextCursor: 'next' },
    });
    await act(async (): Promise<void> => {
      await result.current.loadMore();
    });
    expect(getNotifications).toHaveBeenLastCalledWith({
      size: 20,
      cursor: 'next',
    });
    expect(result.current.loadMoreRequest.status).toBe('success');
  });

  it('일부 읽음 실패는 성공 항목만 반영하고 다음에는 실패한 항목만 읽는다', async (): Promise<void> => {
    const twoItems = {
      ...page,
      unreadCount: 2,
      items: [item, { ...item, id: 'second' }],
    };
    jest.mocked(getNotifications).mockResolvedValueOnce(twoItems);
    const { result } = renderHook(useNotifications);
    await waitFor((): void =>
      expect(result.current.request.status).toBe('success'),
    );
    await act(async (): Promise<void> => {
      await expect(result.current.markRead(twoItems.items)).rejects.toThrow();
    });
    expect(result.current.request).toMatchObject({
      data: { unreadCount: 1, items: [{ read: true }, { read: false }] },
    });
    jest.mocked(markNotificationsRead).mockResolvedValueOnce(['second']);
    await act(async (): Promise<void> => {
      if (result.current.request.status === 'success')
        await result.current.markRead(result.current.request.data.items);
    });
    expect(markNotificationsRead).toHaveBeenLastCalledWith(['second']);
    expect(result.current.request).toMatchObject({ data: { unreadCount: 0 } });
  });
});
