import { beforeEach, describe, expect, it, jest } from '@jest/globals';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import * as reports from '../../domain/reports/reportSubmission';
import { REPORT_COPY } from '../../shared/report/copy';
import DiaryDetailOptionsButton from './DiaryDetailOptionsButton';
import { DIARY_DETAIL_COPY } from './copy';

const mockAddToast = jest.fn();
jest.mock('../../shared/ToastContext', () => ({
  useToastContext: () => ({ addToast: mockAddToast }),
}));
jest.mock('react-router', () => ({ useNavigate: () => jest.fn() }));
jest.mock('./useDiaryDelete', () => ({
  __esModule: true,
  default: () => ({
    request: { status: 'idle' },
    reset: jest.fn(),
    execute: jest.fn(),
  }),
}));
jest.mock('../../assets/icons/feed-more.svg', () => 'more.svg');
jest.mock('../../assets/icons/delete.svg', () => 'delete.svg');
jest.mock('../../assets/icons/report.svg', () => 'report.svg');
jest.mock('../../assets/icons/bottom-sheet-close.svg', () => 'close.svg');

const diaryId = '30000000-0000-4000-8000-000000000001';

beforeEach((): void => {
  jest.restoreAllMocks();
  mockAddToast.mockReset();
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

describe('개인 네컷만화 신고 진입', (): void => {
  it('메뉴에서 네컷만화 신고를 열어 일기 대상으로 접수하고 토스트로 알린다', async (): Promise<void> => {
    const user = userEvent.setup();
    const submit = jest.spyOn(reports, 'submitReport').mockResolvedValue({
      id: '10000000-0000-4000-8000-000000000001',
      status: 'RECEIVED',
      duplicate: false,
    });
    render(<DiaryDetailOptionsButton diaryId={diaryId} />);

    await user.click(
      screen.getByRole('button', { name: DIARY_DETAIL_COPY.menuAction }),
    );
    await user.click(screen.getByRole('button', { name: '네컷만화 신고' }));

    expect(
      await screen.findByRole('heading', { name: '네컷만화 신고' }),
    ).toBeVisible();
    await user.click(screen.getByRole('radio', { name: '기타' }));
    await user.type(screen.getByRole('textbox'), '그림이 일기와 달라요.');
    await user.click(screen.getByRole('button', { name: REPORT_COPY.submit }));

    await waitFor((): void =>
      expect(mockAddToast).toHaveBeenCalledWith(
        { type: 'success', message: REPORT_COPY.received },
        3000,
      ),
    );
    expect(submit).toHaveBeenCalledWith({
      targetType: 'DIARY',
      targetId: diaryId,
      reason: 'OTHER',
      description: '그림이 일기와 달라요.',
    });
  });
});
