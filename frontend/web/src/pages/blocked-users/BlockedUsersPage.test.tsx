import { beforeEach, describe, expect, it, jest } from '@jest/globals';
import { StrictMode, type ComponentProps, type ReactNode } from 'react';
import {
  act,
  fireEvent,
  render,
  screen,
  waitFor,
  within,
} from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import BlockedUsersPage from './BlockedUsersPage';
import { getBlockedUsers, setUserBlocked } from '../../domain/blocks/blocks';
import { setFeedListCache } from '../../domain/feeds/feedListCache';
import { ToastProvider } from '../../shared/ToastContext';

interface MockLinkProps extends ComponentProps<'a'> {
  readonly to: string;
}

jest.mock('react-router', () => ({
  Link: ({ to, ...props }: MockLinkProps) => <a href={to} {...props} />,
}));
jest.mock('../../domain/blocks/blocks', () => ({
  getBlockedUsers: jest.fn(),
  setUserBlocked: jest.fn(),
}));
jest.mock('../../domain/feeds/feedListCache', () => ({
  setFeedListCache: jest.fn(),
}));
jest.mock('../../assets/images/profile-fallback.svg', () => 'fallback.svg');
jest.mock('../../assets/images/loading-animation.webp', () => 'loading.webp');
jest.mock('../../assets/icons/autorenew.svg', () => 'autorenew.svg');
jest.mock('../../assets/icons/back.svg', () => 'back.svg');
jest.mock('../../assets/icons/toast-success.svg', () => 'toast-success.svg');
jest.mock('../../assets/icons/toast-error.svg', () => 'toast-error.svg');
jest.mock('../../assets/icons/toast-loading.svg', () => 'toast-loading.svg');
const TestProviders = ({ children }: { readonly children: ReactNode }) => (
  <ToastProvider>{children}</ToastProvider>
);

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
describe('차단한 사용자 관리 화면', (): void => {
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
    const { container } = render(<BlockedUsersPage />, {
      wrapper: TestProviders,
    });
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
    expect(screen.getByText('차단을 해제했어요.')).toBeVisible();
    expect(
      within(container).queryByText('차단을 해제했어요.'),
    ).not.toBeInTheDocument();
    expect(
      screen.queryByText('차단을 해제하지 못했어요. 다시 시도해 주세요.'),
    ).not.toBeInTheDocument();
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
    expect(getBlockedUsers).toHaveBeenNthCalledWith(3, {
      cursor: undefined,
      signal: expect.any(AbortSignal),
    });
    expect(getBlockedUsers).toHaveBeenNthCalledWith(4, {
      cursor: undefined,
      signal: expect.any(AbortSignal),
    });
  });

  it('해제 중 다시 렌더링해도 중복 조회하지 않고 완료 결과를 유지한다', async (): Promise<void> => {
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
    const { rerender } = render(<BlockedUsersPage />, {
      wrapper: TestProviders,
    });
    await userEvent.click(
      await screen.findByRole('button', { name: '탈퇴한 사용자 차단 해제' }),
    );
    rerender(<BlockedUsersPage />);
    expect(
      screen.getByRole('button', { name: '탈퇴한 사용자 차단 해제' }),
    ).toBeDisabled();
    expect(getBlockedUsers).toHaveBeenCalledTimes(1);
    await act(async (): Promise<void> => {
      finishUnblock();
    });
    expect(screen.getByText('차단한 사용자가 없어요.')).toBeVisible();
    expect(getBlockedUsers).toHaveBeenCalledTimes(2);
    expect(setUserBlocked).toHaveBeenCalledTimes(1);
  });

  it('해제에 실패하면 토스트로 알리고 같은 대상을 다시 시도한다', async (): Promise<void> => {
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
    render(<BlockedUsersPage />, { wrapper: TestProviders });
    await userEvent.click(
      await screen.findByRole('button', { name: '탈퇴한 사용자 차단 해제' }),
    );
    await act(async (): Promise<void> => {
      failUnblock(new Error('해제 실패'));
    });
    expect(
      screen.getByText('차단을 해제하지 못했어요. 다시 시도해 주세요.'),
    ).toBeVisible();
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
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

  it('화면 진입 시 조회하고 다음 페이지를 불러온 뒤 해제 후 최신 목록을 재조회한다', async (): Promise<void> => {
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
    render(<BlockedUsersPage />, { wrapper: TestProviders });
    expect(getBlockedUsers).toHaveBeenCalledTimes(1);
    expect(await screen.findByText('탈퇴한 사용자')).toBeVisible();
    await userEvent.click(screen.getByRole('button', { name: '더 불러오기' }));
    expect(await screen.findByText('네오')).toBeVisible();
    expect(getBlockedUsers).toHaveBeenNthCalledWith(2, {
      cursor: 'cursor',
      signal: expect.any(AbortSignal),
    });
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
    render(<BlockedUsersPage />, { wrapper: TestProviders });
    expect(await screen.findByRole('alert')).toHaveTextContent(
      '차단 목록을 불러오지 못했어요.',
    );
    expect(
      screen.queryByText('차단을 해제하지 못했어요. 다시 시도해 주세요.'),
    ).not.toBeInTheDocument();
    await userEvent.click(
      screen.getByRole('button', { name: '차단 목록 다시 불러오기' }),
    );
    await screen.findByText('탈퇴한 사용자');
    await userEvent.click(
      screen.getByRole('button', { name: '탈퇴한 사용자 차단 해제' }),
    );
    expect(
      await screen.findByText('차단을 해제하지 못했어요. 다시 시도해 주세요.'),
    ).toBeVisible();
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
    expect(
      screen.getByRole('button', { name: '탈퇴한 사용자 차단 해제' }),
    ).toBeEnabled();
    expect(screen.getByText('탈퇴한 사용자')).toBeVisible();
  });

  it('첫 조회 실패는 목록 안에서 재시도하고 요청 중 중복 조회를 막는다', async (): Promise<void> => {
    let completeRetry: (
      data: Awaited<ReturnType<typeof getBlockedUsers>>,
    ) => void = (): void => undefined;
    jest
      .mocked(getBlockedUsers)
      .mockRejectedValueOnce(new Error('조회 실패'))
      .mockImplementationOnce(
        (): ReturnType<typeof getBlockedUsers> =>
          new Promise((resolve): void => {
            completeRetry = resolve;
          }),
      );
    const { container } = render(<BlockedUsersPage />, {
      wrapper: TestProviders,
    });
    expect(await screen.findByRole('alert')).toHaveTextContent(
      '차단 목록을 불러오지 못했어요.',
    );
    expect(within(container).getByRole('alert')).toBeVisible();
    expect(
      screen.queryByText(
        '내가 차단한 사용자만 표시해요. 해제하면 내가 한 차단만 해제돼요.',
      ),
    ).not.toBeInTheDocument();
    const retry = screen.getByRole('button', {
      name: '차단 목록 다시 불러오기',
    });
    fireEvent.click(retry);
    fireEvent.click(retry);
    expect(retry).toBeDisabled();
    expect(getBlockedUsers).toHaveBeenCalledTimes(2);
    await act(async (): Promise<void> => {
      completeRetry({ items: [user], nextCursor: null, hasNext: false });
    });
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
    expect(screen.getByText('탈퇴한 사용자')).toBeVisible();
    expect(setUserBlocked).not.toHaveBeenCalled();
  });

  it('다음 페이지 조회 실패는 현재 목록을 보존하고 같은 커서로 GET만 재시도한다', async (): Promise<void> => {
    const remaining = { ...user, userId: 'user-2', nickname: '네오' };
    let completeRetry: (
      data: Awaited<ReturnType<typeof getBlockedUsers>>,
    ) => void = (): void => undefined;
    jest
      .mocked(getBlockedUsers)
      .mockResolvedValueOnce({
        items: [user],
        nextCursor: 'next-page',
        hasNext: true,
      })
      .mockRejectedValueOnce(new Error('페이지 조회 실패'))
      .mockImplementationOnce(
        (): ReturnType<typeof getBlockedUsers> =>
          new Promise((resolve): void => {
            completeRetry = resolve;
          }),
      );
    const { container } = render(<BlockedUsersPage />, {
      wrapper: TestProviders,
    });
    await userEvent.click(
      await screen.findByRole('button', { name: '더 불러오기' }),
    );
    expect(await screen.findByRole('alert')).toHaveTextContent(
      '차단 목록을 더 불러오지 못했어요.',
    );
    expect(within(container).getByRole('alert')).toBeVisible();
    expect(screen.getByText('탈퇴한 사용자')).toBeVisible();
    expect(
      screen.queryByRole('button', { name: '더 불러오기' }),
    ).not.toBeInTheDocument();
    const retry = screen.getByRole('button', {
      name: '다음 차단 목록 다시 불러오기',
    });
    fireEvent.click(retry);
    fireEvent.click(retry);
    expect(retry).toBeDisabled();
    expect(getBlockedUsers).toHaveBeenCalledTimes(3);
    expect(getBlockedUsers).toHaveBeenNthCalledWith(2, {
      cursor: 'next-page',
      signal: expect.any(AbortSignal),
    });
    expect(getBlockedUsers).toHaveBeenNthCalledWith(3, {
      cursor: 'next-page',
      signal: expect.any(AbortSignal),
    });
    await act(async (): Promise<void> => {
      completeRetry({
        items: [user, remaining],
        nextCursor: null,
        hasNext: false,
      });
    });
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
    expect(screen.getAllByText('탈퇴한 사용자')).toHaveLength(1);
    expect(screen.getByText('네오')).toBeVisible();
    expect(setUserBlocked).not.toHaveBeenCalled();
    expect(setFeedListCache).not.toHaveBeenCalled();
  });

  it('StrictMode에서 취소된 첫 조회가 늦게 끝나도 현재 목록을 덮지 않는다', async (): Promise<void> => {
    let finishPrevious: (
      data: Awaited<ReturnType<typeof getBlockedUsers>>,
    ) => void = (): void => undefined;
    const current = {
      ...user,
      userId: 'current-user',
      nickname: '현재 사용자',
    };
    jest
      .mocked(getBlockedUsers)
      .mockImplementationOnce(
        (): ReturnType<typeof getBlockedUsers> =>
          new Promise((resolve): void => {
            finishPrevious = resolve;
          }),
      )
      .mockResolvedValueOnce({
        items: [current],
        nextCursor: null,
        hasNext: false,
      });
    render(
      <StrictMode>
        <ToastProvider>
          <BlockedUsersPage />
        </ToastProvider>
      </StrictMode>,
    );
    expect(await screen.findByText('현재 사용자')).toBeVisible();
    expect(getBlockedUsers).toHaveBeenCalledTimes(2);
    const firstSignal = jest.mocked(getBlockedUsers).mock.calls[0][0]?.signal;
    expect(firstSignal?.aborted).toBe(true);
    await act(async (): Promise<void> => {
      finishPrevious({ items: [user], nextCursor: null, hasNext: false });
    });
    expect(screen.getByText('현재 사용자')).toBeVisible();
    expect(screen.queryByText('탈퇴한 사용자')).not.toBeInTheDocument();
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
  });

  it.each(['첫 조회 재시도', '다음 페이지', '해제 후 갱신'] as const)(
    '%s GET도 화면을 떠나면 취소하고 늦은 실패를 표시하지 않는다',
    async (phase): Promise<void> => {
      let failRead: (error: Error) => void = (): void => undefined;
      const reads = jest.mocked(getBlockedUsers);
      if (phase === '첫 조회 재시도') {
        reads.mockRejectedValueOnce(new Error('첫 조회 실패'));
      } else {
        reads.mockResolvedValueOnce({
          items: [user],
          nextCursor: phase === '다음 페이지' ? 'next-page' : null,
          hasNext: phase === '다음 페이지',
        });
      }
      reads.mockImplementationOnce(
        (): ReturnType<typeof getBlockedUsers> =>
          new Promise((_resolve, reject): void => {
            failRead = reject;
          }),
      );
      jest.mocked(setUserBlocked).mockResolvedValue(undefined);
      const { unmount } = render(<BlockedUsersPage />, {
        wrapper: TestProviders,
      });
      if (phase === '첫 조회 재시도') {
        await userEvent.click(
          await screen.findByRole('button', {
            name: '차단 목록 다시 불러오기',
          }),
        );
      } else if (phase === '다음 페이지') {
        await userEvent.click(
          await screen.findByRole('button', { name: '더 불러오기' }),
        );
      } else {
        await userEvent.click(
          await screen.findByRole('button', {
            name: '탈퇴한 사용자 차단 해제',
          }),
        );
      }
      await waitFor((): void => expect(reads).toHaveBeenCalledTimes(2));
      const signal = reads.mock.calls[1][0]?.signal;
      expect(signal).toBeInstanceOf(AbortSignal);
      expect(signal?.aborted).toBe(false);
      unmount();
      expect(signal?.aborted).toBe(true);
      await act(async (): Promise<void> => {
        failRead(new DOMException('조회가 취소됐습니다.', 'AbortError'));
      });
      expect(screen.queryByRole('alert')).not.toBeInTheDocument();
      expect(
        screen.queryByText('차단을 해제하지 못했어요. 다시 시도해 주세요.'),
      ).not.toBeInTheDocument();
      expect(setUserBlocked).toHaveBeenCalledTimes(
        phase === '해제 후 갱신' ? 1 : 0,
      );
    },
  );

  it('해제 도중 화면을 떠나도 DELETE 성공 후 캐시는 비우고 후속 조회는 보내지 않는다', async (): Promise<void> => {
    let finishUnblock: () => void = (): void => undefined;
    jest.mocked(getBlockedUsers).mockResolvedValueOnce({
      items: [user],
      nextCursor: null,
      hasNext: false,
    });
    jest.mocked(setUserBlocked).mockImplementationOnce(
      (): Promise<void> =>
        new Promise((resolve): void => {
          finishUnblock = resolve;
        }),
    );
    const { unmount } = render(<BlockedUsersPage />, {
      wrapper: TestProviders,
    });
    await userEvent.click(
      await screen.findByRole('button', { name: '탈퇴한 사용자 차단 해제' }),
    );
    expect(setUserBlocked).toHaveBeenCalledWith(user.userId, false);
    expect(setFeedListCache).not.toHaveBeenCalled();
    unmount();
    await act(async (): Promise<void> => {
      finishUnblock();
    });
    expect(setFeedListCache).toHaveBeenCalledWith(null);
    expect(getBlockedUsers).toHaveBeenCalledTimes(1);
    expect(screen.queryByText('차단을 해제했어요.')).not.toBeInTheDocument();
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
  });
});
