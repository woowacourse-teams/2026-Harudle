import { beforeEach, describe, expect, it, jest } from '@jest/globals';
import { render, screen, waitFor } from '@testing-library/react';
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
