import { beforeEach, describe, expect, it, jest } from '@jest/globals';
import { act, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import AdminReportsPanel from './AdminReportsPanel';
import {
  getAdminReport,
  getAdminReports,
  reviewAdminReport,
  type AdminReportDetail,
  type AdminReviewResult,
} from './adminModerationApi';
import { ToastProvider } from '../../shared/ToastContext';
import { RequestError } from '../../shared/api';
jest.mock('react-router', () => {
  const React = jest.requireActual<typeof import('react')>('react');
  return {
    useSearchParams: function useSearchParams() {
      const [params, setParams] = React.useState<URLSearchParams>(
        () => new URLSearchParams('view=reports'),
      );
      return [params, (next: URLSearchParams): void => setParams(next)];
    },
  };
});
jest.mock('./adminModerationApi', () => ({
  ...jest.requireActual<typeof import('./adminModerationApi')>(
    './adminModerationApi',
  ),
  getAdminReport: jest.fn(),
  getAdminReports: jest.fn(),
  reviewAdminReport: jest.fn(),
}));
jest.mock('./AdminModerationPanel', () => ({
  __esModule: true,
  default: ({
    targetType,
    targetId,
    available,
  }: {
    readonly targetType: string;
    readonly targetId: string;
    readonly available: boolean;
  }) => (
    <span data-testid={`${targetType}-${targetId}`}>
      {available ? '조치 가능' : '조치 불가'}
    </span>
  ),
}));
jest.mock('../../assets/images/loading-animation.webp', () => 'loading.webp');
jest.mock('../../assets/icons/toast-success.svg', () => 'success.svg');
jest.mock('../../assets/icons/toast-error.svg', () => 'error.svg');
jest.mock('../../assets/icons/toast-loading.svg', () => 'loading.svg');
const first: AdminReportDetail = {
  report: {
    id: 'report-1',
    reporterId: null,
    targetType: 'FEED',
    targetId: 'feed-1',
    targetUserId: 'user-1',
    reason: 'SPAM',
    description: '광고 반복',
    status: 'RECEIVED',
    createdAt: '2026-10-23T03:00:00Z',
    reviewedAt: null,
    reviewedBy: null,
    reviewReason: null,
  },
  target: {
    exists: true,
    authorId: 'user-1',
    authorNickname: '네오',
    authorDeleted: false,
    deleted: false,
    parentDeleted: false,
    hidden: false,
    parentHidden: true,
    publiclyVisible: false,
    feedId: 'feed-1',
    title: '제목',
    imageUrl: null,
    imageUrlExpiresAt: null,
    commentContent: null,
  },
};
const second: AdminReportDetail = {
  ...first,
  report: {
    ...first.report,
    id: 'report-2',
    targetType: 'COMMENT',
    targetId: 'comment-2',
  },
  target: {
    ...first.target,
    exists: false,
    deleted: true,
    authorDeleted: true,
    authorId: null,
  },
};
const reviewed: AdminReviewResult = {
  id: 'report-1',
  status: 'RESOLVED',
  reviewedBy: 'admin-1',
  reviewedAt: '2026-10-23T04:00:00Z',
  reviewReason: '정책 위반 확인',
};
beforeEach((): void => {
  jest
    .mocked(getAdminReports)
    .mockReset()
    .mockImplementation(async (status) => ({
      content: status === 'RECEIVED' ? [first.report, second.report] : [],
      page: 0,
      size: 20,
      totalElements: status === 'RECEIVED' ? 2 : 0,
      totalPages: 1,
      hasNext: false,
    }));
  jest.mocked(getAdminReport).mockReset().mockResolvedValue(first);
  jest.mocked(reviewAdminReport).mockReset();
  Object.defineProperties(HTMLDialogElement.prototype, {
    showModal: {
      configurable: true,
      value(this: HTMLDialogElement): void {
        this.setAttribute('open', '');
      },
    },
    close: {
      configurable: true,
      value(this: HTMLDialogElement): void {
        this.removeAttribute('open');
      },
    },
  });
});
const openReview = async (): Promise<HTMLElement> => {
  await userEvent.click(
    await screen.findByRole('button', { name: '피드 신고 report-1' }),
  );
  await userEvent.click(
    await within(await screen.findByLabelText('신고 상세')).findByRole(
      'button',
      { name: '처리 완료' },
    ),
  );
  const dialog = screen
    .getByRole('heading', { name: '신고 처리 완료' })
    .closest('dialog');
  if (!dialog) throw new Error('신고 처리 확인창이 없습니다.');
  return dialog;
};
describe('관리자 신고 심사', (): void => {
  it('연결된 숨김 상태를 확인하고 사유와 함께 종결한 신고는 다시 변경하지 않는다', async (): Promise<void> => {
    jest
      .mocked(reviewAdminReport)
      .mockImplementation(async (): Promise<AdminReviewResult> => {
        jest.mocked(getAdminReport).mockResolvedValue({
          ...first,
          report: { ...first.report, ...reviewed },
        });
        return reviewed;
      });
    render(<AdminReportsPanel />, { wrapper: ToastProvider });
    const dialog = await openReview();
    expect(
      screen.getByText('다른 연결된 피드의 관리자 숨김이 유지돼요.'),
    ).toBeVisible();
    expect(
      within(dialog).getByRole('button', { name: '확인하기' }),
    ).toBeDisabled();
    await userEvent.type(within(dialog).getByRole('textbox'), '정책 위반 확인');
    await userEvent.click(
      within(dialog).getByRole('button', { name: '확인하기' }),
    );
    await within(dialog).findByRole('status');
    await userEvent.click(within(dialog).getByRole('button', { name: '닫기' }));
    expect(
      await screen.findByRole('heading', { name: '신고 상세 · 처리 완료' }),
    ).toBeVisible();
    expect(
      within(screen.getByLabelText('신고 상세')).queryByRole('button', {
        name: '처리 완료',
      }),
    ).not.toBeInTheDocument();
    expect(screen.getByText('정책 위반 확인')).toBeVisible();
    expect(reviewAdminReport).toHaveBeenCalledTimes(1);
    expect(reviewAdminReport).toHaveBeenCalledWith(
      'report-1',
      'RESOLVED',
      '정책 위반 확인',
    );
    await userEvent.click(screen.getByRole('button', { name: '반려' }));
    await waitFor((): void =>
      expect(getAdminReports).toHaveBeenLastCalledWith(
        'DISMISSED',
        0,
        expect.any(AbortSignal),
      ),
    );
    expect(await screen.findByText('이 상태의 신고가 없어요.')).toBeVisible();
  });
  it('충돌한 심사는 입력을 남기고 GET 새로고침 전 자동 재전송하지 않는다', async (): Promise<void> => {
    jest.mocked(reviewAdminReport).mockRejectedValue(
      new RequestError({
        type: 'urn:conflict',
        title: 'Conflict',
        status: 409,
        detail: '다른 관리자가 처리',
        instance: '/report',
        code: 'REPORT_ALREADY_REVIEWED',
      }),
    );
    render(<AdminReportsPanel />, { wrapper: ToastProvider });
    const dialog = await openReview();
    await userEvent.type(within(dialog).getByRole('textbox'), '확인한 사유');
    await userEvent.click(
      within(dialog).getByRole('button', { name: '확인하기' }),
    );
    expect(await within(dialog).findByRole('alert')).toHaveTextContent(
      '다른 관리자가 변경',
    );
    expect(within(dialog).getByRole('textbox')).toHaveValue('확인한 사유');
    await userEvent.click(within(dialog).getByRole('button', { name: '닫기' }));
    expect(
      within(screen.getByLabelText('신고 상세')).getByRole('button', {
        name: '처리 완료',
      }),
    ).toBeDisabled();
    await userEvent.click(
      screen.getByRole('button', { name: '다시 불러오기' }),
    );
    await waitFor((): void =>
      expect(
        within(screen.getByLabelText('신고 상세')).getByRole('button', {
          name: '처리 완료',
        }),
      ).toBeEnabled(),
    );
    expect(reviewAdminReport).toHaveBeenCalledTimes(1);
    expect(getAdminReport).toHaveBeenCalledTimes(2);
  });
  it('선택을 바꾸면 이전 상세 GET을 취소하고 삭제 대상도 신고 기록은 보여준다', async (): Promise<void> => {
    let finish: (value: AdminReportDetail) => void = (): void => undefined;
    jest
      .mocked(getAdminReport)
      .mockImplementationOnce(
        (): Promise<AdminReportDetail> =>
          new Promise((resolve): void => {
            finish = resolve;
          }),
      )
      .mockResolvedValueOnce(second);
    render(<AdminReportsPanel />, { wrapper: ToastProvider });
    await userEvent.click(
      await screen.findByRole('button', { name: '피드 신고 report-1' }),
    );
    const signal = jest.mocked(getAdminReport).mock.calls[0][1];
    await userEvent.click(
      screen.getByRole('button', { name: '댓글 신고 report-2' }),
    );
    expect(await screen.findByText('대상 댓글 · comment-2')).toBeVisible();
    expect(screen.getByTestId('COMMENT-comment-2')).toHaveTextContent(
      '조치 불가',
    );
    expect(screen.getByTestId('USER-user-1')).toHaveTextContent('조치 불가');
    expect(
      within(screen.getByLabelText('신고 상세')).getByRole('button', {
        name: '처리 완료',
      }),
    ).toBeEnabled();
    expect(signal?.aborted).toBe(true);
    await act(async (): Promise<void> => finish(first));
    expect(screen.getByText('대상 댓글 · comment-2')).toBeVisible();
    expect(screen.queryByText('대상 피드 · feed-1')).not.toBeInTheDocument();
  });
  it('최초 심사 관리자가 삭제되어도 종결 사유를 유지하고 읽기 전용으로 보여준다', async (): Promise<void> => {
    jest.mocked(getAdminReport).mockResolvedValueOnce({
      ...first,
      report: { ...first.report, ...reviewed, reviewedBy: null },
    });
    render(<AdminReportsPanel />, { wrapper: ToastProvider });
    await userEvent.click(
      await screen.findByRole('button', { name: '피드 신고 report-1' }),
    );
    expect(
      await screen.findByRole('heading', { name: '신고 상세 · 처리 완료' }),
    ).toBeVisible();
    expect(screen.getByText(/처리자 삭제된 관리자/)).toBeVisible();
    expect(screen.getByText('정책 위반 확인')).toBeVisible();
    expect(
      within(screen.getByLabelText('신고 상세')).queryByRole('button', {
        name: '처리 완료',
      }),
    ).not.toBeInTheDocument();
  });
});
