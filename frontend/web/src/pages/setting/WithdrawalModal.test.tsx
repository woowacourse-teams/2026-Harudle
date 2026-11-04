import { beforeEach, describe, expect, it, jest } from '@jest/globals';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import {
  requestWithdrawal,
  type WithdrawalResponse,
} from '../../domain/user/withdrawal';
import WithdrawalModal from './WithdrawalModal';

jest.mock('../../domain/user/withdrawal', () => ({
  ...jest.requireActual<typeof import('../../domain/user/withdrawal')>(
    '../../domain/user/withdrawal',
  ),
  requestWithdrawal: jest.fn<() => Promise<WithdrawalResponse>>(),
}));
jest.mock('../../posthog/useAnalytics', () => ({
  useAnalytics: () => ({ resetUser: jest.fn() }),
}));

beforeEach((): void => {
  jest.mocked(requestWithdrawal).mockReset();
  Object.defineProperty(HTMLDialogElement.prototype, 'showModal', {
    configurable: true,
    value: function (this: HTMLDialogElement): void {
      this.setAttribute('open', '');
    },
  });
});

describe('회원 탈퇴 확인', (): void => {
  it('더 둘러보기를 선택하면 탈퇴를 요청하지 않는다', async (): Promise<void> => {
    const onClose = jest.fn();
    render(<WithdrawalModal onClose={onClose} />);
    expect(
      screen.getByText(
        '탈퇴하면 계정, 일기와 작성한 피드가 삭제되며 복구할 수 없어요.',
      ),
    ).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: '더 둘러보기' }));
    expect(onClose).toHaveBeenCalledTimes(1);
    expect(requestWithdrawal).not.toHaveBeenCalled();
  });

  it('탈퇴 요청이 실패하면 안내를 보여주고 다시 시도할 수 있다', async (): Promise<void> => {
    jest.mocked(requestWithdrawal).mockRejectedValue(new Error());
    render(<WithdrawalModal onClose={jest.fn()} />);
    await userEvent.click(screen.getByRole('button', { name: '탈퇴하기' }));
    expect(await screen.findByRole('alert')).toHaveTextContent(
      '회원 탈퇴 요청을 처리하지 못했어요.',
    );
    expect(screen.getByRole('button', { name: '탈퇴하기' })).toBeEnabled();
  });

  it('요청을 처리하는 동안 다시 요청하거나 창을 닫을 수 없다', async (): Promise<void> => {
    let rejectRequest!: (reason: Error) => void;
    jest.mocked(requestWithdrawal).mockReturnValue(
      new Promise((_resolve, reject): void => {
        rejectRequest = reject;
      }),
    );
    render(<WithdrawalModal onClose={jest.fn()} />);
    await userEvent.click(screen.getByRole('button', { name: '탈퇴하기' }));
    expect(screen.getByRole('button', { name: '처리 중...' })).toBeDisabled();
    expect(screen.getByRole('button', { name: '더 둘러보기' })).toBeDisabled();
    expect(requestWithdrawal).toHaveBeenCalledTimes(1);
    rejectRequest(new Error());
    expect(await screen.findByRole('alert')).toBeInTheDocument();
  });

  it('애플 직접 해제가 필요하면 탈퇴 완료 후 안내한다', async (): Promise<void> => {
    localStorage.setItem('harudle.has-completed-oauth', 'true');
    jest.mocked(requestWithdrawal).mockResolvedValue({
      status: 'COMPLETED',
      authorizationUrl: null,
      appleManualUnlinkRequired: true,
    });
    render(<WithdrawalModal onClose={jest.fn()} />);
    await userEvent.click(screen.getByRole('button', { name: '탈퇴하기' }));
    expect(
      await screen.findByText('회원 탈퇴가 완료됐어요'),
    ).toBeInTheDocument();
    expect(
      screen.getByText(
        '애플 계정에 하루들 연결이 남아 있을 수 있어요. 애플 계정 설정에서 연결을 해제해 주세요.',
      ),
    ).toBeInTheDocument();
    expect(
      screen.getByRole('link', { name: '애플 연결 해제 방법 보기' }),
    ).toHaveAttribute(
      'href',
      'https://support.apple.com/ko-kr/guide/iphone/iph238921d37/ios',
    );
    expect(localStorage.getItem('harudle.has-completed-oauth')).toBeNull();
  });
});
