import { beforeEach, describe, expect, it, jest } from '@jest/globals';
import { act, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import BlockedUsers from './BlockedUsers';
import { getBlockedUsers, setUserBlocked } from '../../domain/blocks/blocks';
import { setFeedListCache } from '../../domain/feeds/feedListCache';
jest.mock('../../domain/blocks/blocks', () => ({
  getBlockedUsers: jest.fn(),
  setUserBlocked: jest.fn(),
}));
jest.mock('../../domain/feeds/feedListCache', () => ({
  setFeedListCache: jest.fn(),
}));
jest.mock('../../assets/images/profile-fallback.svg', () => 'fallback.svg');
const user = {
  userId: 'user-1',
  nickname: '탈퇴한 사용자',
  profileImageUrl: null,
  blockedAt: '2026-10-22T00:00:00Z',
};
beforeEach((): void => {
  jest.mocked(getBlockedUsers).mockReset();
  jest.mocked(setUserBlocked).mockReset();
});
describe('설정의 차단 목록', (): void => {
  it('해제 직후 목록에서 제거하고 갱신 실패는 DELETE 없이 GET만 재시도한다', async (): Promise<void> => {
    const remaining = { ...user, userId: 'user-2', nickname: '네오' };
    let failRefresh: (error: Error) => void = (): void => undefined;
    jest
      .mocked(getBlockedUsers)
      .mockResolvedValueOnce({
        items: [user, remaining],
        nextCursor: null,
        hasNext: false,
      })
      .mockImplementationOnce(
        (): ReturnType<typeof getBlockedUsers> =>
          new Promise((_resolve, reject): void => {
            failRefresh = reject;
          }),
      )
      .mockRejectedValueOnce(new Error('목록 갱신 실패'))
      .mockResolvedValueOnce({
        items: [remaining],
        nextCursor: null,
        hasNext: false,
      });
    jest.mocked(setUserBlocked).mockResolvedValue(undefined);
    render(<BlockedUsers />);
    await userEvent.click(
      screen.getByRole('button', { name: '차단한 사용자' }),
    );
    await userEvent.click(
      await screen.findByRole('button', { name: '탈퇴한 사용자 차단 해제' }),
    );
    await waitFor((): void =>
      expect(screen.queryByText('탈퇴한 사용자')).not.toBeInTheDocument(),
    );
    expect(screen.getByText('네오')).toBeVisible();
    expect(setFeedListCache).toHaveBeenCalledWith(null);
    await act(async (): Promise<void> => {
      failRefresh(new Error('목록 갱신 실패'));
    });
    expect(screen.getByRole('status')).toHaveTextContent('차단을 해제했어요.');
    expect(screen.getByRole('alert')).toHaveTextContent(
      '차단 목록을 갱신하지 못했어요.',
    );
    await userEvent.click(
      screen.getByRole('button', { name: '차단 목록 다시 불러오기' }),
    );
    expect(await screen.findByRole('alert')).toHaveTextContent(
      '차단 목록을 갱신하지 못했어요.',
    );
    expect(
      screen.getByRole('button', { name: '차단 목록 다시 불러오기' }),
    ).toBeEnabled();
    await userEvent.click(
      screen.getByRole('button', { name: '차단 목록 다시 불러오기' }),
    );
    await waitFor((): void =>
      expect(screen.queryByRole('alert')).not.toBeInTheDocument(),
    );
    expect(
      screen.queryByRole('button', { name: '차단 목록 다시 불러오기' }),
    ).not.toBeInTheDocument();
    expect(screen.getByText('네오')).toBeVisible();
    expect(setUserBlocked).toHaveBeenCalledTimes(1);
    expect(getBlockedUsers).toHaveBeenCalledTimes(4);
    expect(getBlockedUsers).toHaveBeenNthCalledWith(3, { cursor: undefined });
    expect(getBlockedUsers).toHaveBeenNthCalledWith(4, { cursor: undefined });
  });

  it('해제 중 접었다 펼쳐도 중복 조회하지 않고 숨겨진 동안 완료된 결과를 유지한다', async (): Promise<void> => {
    let blocked = true;
    let finishUnblock: () => void = (): void => undefined;
    jest
      .mocked(getBlockedUsers)
      .mockImplementation(async (): ReturnType<typeof getBlockedUsers> => ({
        items: blocked ? [user] : [],
        nextCursor: null,
        hasNext: false,
      }));
    jest.mocked(setUserBlocked).mockImplementation(
      (): Promise<void> =>
        new Promise((resolve): void => {
          finishUnblock = (): void => {
            blocked = false;
            resolve();
          };
        }),
    );
    render(<BlockedUsers />);
    const toggle = screen.getByRole('button', { name: '차단한 사용자' });
    await userEvent.click(toggle);
    await userEvent.click(
      await screen.findByRole('button', { name: '탈퇴한 사용자 차단 해제' }),
    );
    await userEvent.click(toggle);
    expect(toggle).toHaveAttribute('aria-expanded', 'false');
    expect(
      screen.queryByRole('button', { name: '탈퇴한 사용자 차단 해제' }),
    ).not.toBeInTheDocument();
    await userEvent.click(toggle);
    expect(
      screen.getByRole('button', { name: '탈퇴한 사용자 차단 해제' }),
    ).toBeDisabled();
    expect(getBlockedUsers).toHaveBeenCalledTimes(1);
    await userEvent.click(toggle);
    await act(async (): Promise<void> => {
      finishUnblock();
    });
    expect(screen.getByText('차단한 사용자가 없어요.')).not.toBeVisible();
    await userEvent.click(toggle);
    expect(screen.getByText('차단한 사용자가 없어요.')).toBeVisible();
    expect(getBlockedUsers).toHaveBeenCalledTimes(2);
    expect(setUserBlocked).toHaveBeenCalledTimes(1);
  });

  it('접힌 동안 해제에 실패해도 다시 펼치면 오류와 재시도 가능한 대상을 보존한다', async (): Promise<void> => {
    let failUnblock: (error: Error) => void = (): void => undefined;
    jest
      .mocked(getBlockedUsers)
      .mockResolvedValue({ items: [user], nextCursor: null, hasNext: false });
    jest.mocked(setUserBlocked).mockImplementation(
      (): Promise<void> =>
        new Promise((_resolve, reject): void => {
          failUnblock = reject;
        }),
    );
    render(<BlockedUsers />);
    const toggle = screen.getByRole('button', { name: '차단한 사용자' });
    await userEvent.click(toggle);
    await userEvent.click(
      await screen.findByRole('button', { name: '탈퇴한 사용자 차단 해제' }),
    );
    await userEvent.click(toggle);
    await act(async (): Promise<void> => {
      failUnblock(new Error('해제 실패'));
    });
    await userEvent.click(toggle);
    expect(screen.getByRole('alert')).toHaveTextContent('해제 실패');
    expect(
      screen.getByRole('button', { name: '탈퇴한 사용자 차단 해제' }),
    ).toBeEnabled();
    expect(getBlockedUsers).toHaveBeenCalledTimes(1);
    jest.mocked(setUserBlocked).mockResolvedValueOnce(undefined);
    jest
      .mocked(getBlockedUsers)
      .mockResolvedValueOnce({ items: [], nextCursor: null, hasNext: false });
    await userEvent.click(
      screen.getByRole('button', { name: '탈퇴한 사용자 차단 해제' }),
    );
    expect(await screen.findByText('차단한 사용자가 없어요.')).toBeVisible();
    expect(setUserBlocked).toHaveBeenCalledTimes(2);
  });

  it('펼칠 때 조회하고 다음 페이지를 불러온 뒤 해제 후 최신 목록을 재조회한다', async (): Promise<void> => {
    jest
      .mocked(getBlockedUsers)
      .mockResolvedValueOnce({
        items: [user],
        nextCursor: 'cursor',
        hasNext: true,
      })
      .mockResolvedValueOnce({
        items: [{ ...user, userId: 'user-2', nickname: '네오' }],
        nextCursor: null,
        hasNext: false,
      })
      .mockResolvedValueOnce({
        items: [{ ...user, userId: 'user-2', nickname: '네오' }],
        nextCursor: null,
        hasNext: false,
      });
    jest.mocked(setUserBlocked).mockResolvedValue(undefined);
    render(<BlockedUsers />);
    expect(getBlockedUsers).not.toHaveBeenCalled();
    await userEvent.click(
      screen.getByRole('button', { name: '차단한 사용자' }),
    );
    expect(await screen.findByText('탈퇴한 사용자')).toBeVisible();
    await userEvent.click(screen.getByRole('button', { name: '더 불러오기' }));
    expect(await screen.findByText('네오')).toBeVisible();
    expect(getBlockedUsers).toHaveBeenNthCalledWith(2, { cursor: 'cursor' });
    await userEvent.click(
      screen.getByRole('button', { name: '탈퇴한 사용자 차단 해제' }),
    );
    await waitFor((): void =>
      expect(screen.queryByText('탈퇴한 사용자')).not.toBeInTheDocument(),
    );
    expect(setUserBlocked).toHaveBeenCalledWith('user-1', false);
    expect(setFeedListCache).toHaveBeenCalledWith(null);
    expect(getBlockedUsers).toHaveBeenCalledTimes(3);
  });
  it('목록 실패는 재시도하고 해제 실패는 목록과 버튼을 보존한다', async (): Promise<void> => {
    jest
      .mocked(getBlockedUsers)
      .mockRejectedValueOnce(new Error('조회 실패'))
      .mockResolvedValue({ items: [user], nextCursor: null, hasNext: false });
    jest.mocked(setUserBlocked).mockRejectedValue(new Error('해제 실패'));
    render(<BlockedUsers />);
    await userEvent.click(
      screen.getByRole('button', { name: '차단한 사용자' }),
    );
    expect(await screen.findByRole('alert')).toHaveTextContent('조회 실패');
    await userEvent.click(
      screen.getByRole('button', { name: '다시 불러오기' }),
    );
    await screen.findByText('탈퇴한 사용자');
    await userEvent.click(
      screen.getByRole('button', { name: '탈퇴한 사용자 차단 해제' }),
    );
    expect(await screen.findByRole('alert')).toHaveTextContent('해제 실패');
    expect(
      screen.getByRole('button', { name: '탈퇴한 사용자 차단 해제' }),
    ).toBeEnabled();
    expect(screen.getByText('탈퇴한 사용자')).toBeVisible();
  });
});
