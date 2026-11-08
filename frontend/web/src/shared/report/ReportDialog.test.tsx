import { beforeEach, describe, expect, it, jest } from '@jest/globals';
import {
  act,
  fireEvent,
  render,
  screen,
  type RenderResult,
} from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { createRef, type RefObject } from 'react';
import * as reports from '../../domain/reports/reportSubmission';
import type { ModalHandle } from '../Modal';
import { ToastProvider } from '../ToastContext';
import ReportDialog from './ReportDialog';
import { REPORT_COPY } from './copy';

jest.mock('../../assets/icons/toast-success.svg', () => 'toast-success.svg');
jest.mock('../../assets/icons/toast-error.svg', () => 'toast-error.svg');
jest.mock('../../assets/icons/toast-loading.svg', () => 'toast-loading.svg');

const target: reports.ReportTarget = {
  targetType: 'FEED',
  targetId: '00000000-0000-4000-8000-000000000002',
};
const authorTarget: reports.ReportTarget = {
  targetType: 'USER',
  targetId: '20000000-0000-4000-8000-000000000001',
};
const response: reports.ReportResponse = {
  id: '10000000-0000-4000-8000-000000000001',
  status: 'RECEIVED',
  duplicate: false,
};

interface ReportRenderResult extends RenderResult {
  readonly ref: RefObject<ModalHandle | null>;
  readonly handle: ModalHandle;
}

const renderReport = (): ReportRenderResult => {
  const ref = createRef<ModalHandle>();
  const result = render(
    <ToastProvider>
      <ReportDialog ref={ref} target={target} />
    </ToastProvider>,
  );
  document.querySelectorAll('dialog').forEach((dialog): void => {
    Object.defineProperties(dialog, {
      showModal: {
        value: jest.fn((): void => dialog.setAttribute('open', '')),
      },
      close: { value: (): void => dialog.removeAttribute('open') },
    });
  });
  if (!ref.current) throw new Error('신고창이 없습니다.');
  return { ...result, ref, handle: ref.current };
};

beforeEach((): void => {
  jest.restoreAllMocks();
});

describe('신고 입력과 접수 결과', (): void => {
  it('열기 전에는 신고 입력과 제출 버튼을 화면에 표시하지 않는다', (): void => {
    renderReport();

    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    expect(screen.queryByRole('radio')).not.toBeInTheDocument();
    expect(screen.queryByRole('textbox')).not.toBeInTheDocument();
    expect(
      screen.queryByRole('button', { name: REPORT_COPY.submit }),
    ).not.toBeInTheDocument();
  });

  it('접수 중 중복 제출과 닫기·재초기화를 막고 성공하면 닫고 토스트로 알린다', async (): Promise<void> => {
    const user = userEvent.setup();
    let finish: ((value: reports.ReportResponse) => void) | undefined;
    const submit = jest.spyOn(reports, 'submitReport').mockImplementation(
      (): Promise<reports.ReportResponse> =>
        new Promise((resolve): void => {
          finish = resolve;
        }),
    );
    const { handle } = renderReport();
    act((): void => handle.open());
    expect(
      screen.getByRole('button', { name: REPORT_COPY.submit }),
    ).toBeDisabled();
    await user.click(screen.getByRole('radio', { name: '스팸·광고' }));
    await user.type(screen.getByRole('textbox'), '반복 광고예요.');
    await user.click(screen.getByRole('button', { name: REPORT_COPY.submit }));
    expect(
      screen.getByRole('button', { name: REPORT_COPY.submitting }),
    ).toBeDisabled();
    expect(screen.getByRole('textbox')).toBeDisabled();
    expect(
      screen.getByRole('button', { name: REPORT_COPY.cancel }),
    ).toBeDisabled();
    const dialog = screen.getByRole('dialog');
    if (!(dialog instanceof HTMLDialogElement))
      throw new Error('신고창은 네이티브 dialog여야 합니다.');
    const cancel = new Event('cancel', { cancelable: true });
    fireEvent(dialog, cancel);
    expect(cancel.defaultPrevented).toBe(true);
    fireEvent.click(dialog);
    await user.click(screen.getByRole('button', { name: REPORT_COPY.cancel }));
    const form = screen.getByRole('textbox').closest('form');
    if (!form) throw new Error('신고 입력 폼이 없습니다.');
    fireEvent.submit(form);
    act((): void => {
      handle.close();
      handle.open();
    });
    expect(dialog).toHaveAttribute('open');
    expect(dialog.showModal).toHaveBeenCalledTimes(1);
    expect(screen.getByRole('textbox')).toHaveValue('반복 광고예요.');
    expect(submit).toHaveBeenCalledTimes(1);
    expect(submit).toHaveBeenCalledWith({
      ...target,
      reason: 'SPAM',
      description: '반복 광고예요.',
    });

    await act(async (): Promise<void> => {
      finish?.(response);
    });
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    expect(screen.getByText(REPORT_COPY.received)).toBeInTheDocument();
    expect(
      screen.queryByRole('button', { name: '확인' }),
    ).not.toBeInTheDocument();

    act((): void => handle.open());
    expect(screen.getByRole('textbox')).toHaveValue('');
    expect(screen.getByRole('radio', { name: '스팸·광고' })).not.toBeChecked();
    expect(
      screen.getByRole('button', { name: REPORT_COPY.submit }),
    ).toBeDisabled();
  });

  it('실패해도 입력을 유지하고 재시도 후 닫으며 중복 접수 토스트로 알린다', async (): Promise<void> => {
    const user = userEvent.setup();
    const submit = jest
      .spyOn(reports, 'submitReport')
      .mockRejectedValueOnce(new Error('다시 시도해 주세요.'))
      .mockResolvedValueOnce({ ...response, duplicate: true });
    const { handle } = renderReport();
    act((): void => handle.open());
    await user.click(screen.getByRole('radio', { name: '괴롭힘·욕설' }));
    await user.type(screen.getByRole('textbox'), '댓글에서 욕설을 반복해요.');
    await user.click(screen.getByRole('button', { name: REPORT_COPY.submit }));
    expect(await screen.findByRole('alert')).toHaveTextContent(
      '다시 시도해 주세요.',
    );
    expect(screen.getByRole('textbox')).toHaveValue(
      '댓글에서 욕설을 반복해요.',
    );
    expect(screen.getByRole('radio', { name: '괴롭힘·욕설' })).toBeChecked();

    await user.click(screen.getByRole('button', { name: REPORT_COPY.submit }));
    expect(await screen.findByText(REPORT_COPY.duplicate)).toBeInTheDocument();
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    expect(screen.queryByText(REPORT_COPY.received)).not.toBeInTheDocument();
    expect(submit).toHaveBeenNthCalledWith(2, {
      ...target,
      reason: 'HARASSMENT',
      description: '댓글에서 욕설을 반복해요.',
    });

    act((): void => handle.open());
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
    expect(screen.getByRole('textbox')).toHaveValue('');
  });

  it('상세 내용은 emoji도 한 글자로 세고 500자를 넘으면 제출을 막는다', async (): Promise<void> => {
    const user = userEvent.setup();
    const submit = jest.spyOn(reports, 'submitReport');
    const { handle } = renderReport();
    act((): void => handle.open());
    await user.click(screen.getByRole('radio', { name: '기타' }));
    fireEvent.change(screen.getByRole('textbox'), {
      target: { value: '😀'.repeat(500) },
    });
    expect(screen.getByText('500 / 500')).toBeInTheDocument();
    expect(
      screen.getByRole('button', { name: REPORT_COPY.submit }),
    ).toBeEnabled();
    fireEvent.change(screen.getByRole('textbox'), {
      target: { value: '😀'.repeat(501) },
    });
    expect(screen.getByRole('alert')).toHaveTextContent(
      reports.REPORT_ERRORS.description,
    );
    expect(
      screen.getByRole('button', { name: REPORT_COPY.submit }),
    ).toBeDisabled();
    expect(submit).not.toHaveBeenCalled();
  });

  it.each(['success', 'error'] as const)(
    '제출 중 대상이 변경되면 이전 %s 결과를 새 신고에 적용하지 않는다',
    async (result): Promise<void> => {
      const user = userEvent.setup();
      let finish: ((value: reports.ReportResponse) => void) | undefined;
      let fail: ((reason: Error) => void) | undefined;
      const submit = jest.spyOn(reports, 'submitReport').mockImplementation(
        (): Promise<reports.ReportResponse> =>
          new Promise((resolve, reject): void => {
            finish = resolve;
            fail = reject;
          }),
      );
      const { handle, ref, rerender } = renderReport();
      act((): void => handle.open());
      await user.click(screen.getByRole('radio', { name: '스팸·광고' }));
      await user.type(screen.getByRole('textbox'), '이전 피드의 신고예요.');
      await user.click(
        screen.getByRole('button', { name: REPORT_COPY.submit }),
      );
      rerender(
        <ToastProvider>
          <ReportDialog ref={ref} target={authorTarget} />
        </ToastProvider>,
      );
      act((): void => handle.open());
      expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
      expect(submit).toHaveBeenCalledTimes(1);

      await act(async (): Promise<void> => {
        if (result === 'success') finish?.(response);
        else fail?.(new Error('이전 요청의 오류예요.'));
      });
      expect(screen.queryByText(REPORT_COPY.received)).not.toBeInTheDocument();
      expect(
        screen.queryByText('이전 요청의 오류예요.'),
      ).not.toBeInTheDocument();

      act((): void => handle.open());
      expect(
        screen.getByRole('heading', { name: '작성자 신고' }),
      ).toBeInTheDocument();
      expect(screen.getByRole('textbox')).toHaveValue('');
      submit.mockResolvedValueOnce(response);
      await user.click(screen.getByRole('radio', { name: '기타' }));
      await user.click(
        screen.getByRole('button', { name: REPORT_COPY.submit }),
      );
      expect(submit).toHaveBeenLastCalledWith({
        ...authorTarget,
        reason: 'OTHER',
        description: '',
      });
      expect(await screen.findByText(REPORT_COPY.received)).toBeInTheDocument();
    },
  );

  it('신고창이 언마운트된 뒤 도착한 접수 결과는 토스트로 표시하지 않는다', async (): Promise<void> => {
    const user = userEvent.setup();
    let finish: ((value: reports.ReportResponse) => void) | undefined;
    jest.spyOn(reports, 'submitReport').mockImplementation(
      (): Promise<reports.ReportResponse> =>
        new Promise((resolve): void => {
          finish = resolve;
        }),
    );
    const { handle, rerender } = renderReport();
    act((): void => handle.open());
    await user.click(screen.getByRole('radio', { name: '기타' }));
    await user.click(screen.getByRole('button', { name: REPORT_COPY.submit }));
    rerender(<ToastProvider>{null}</ToastProvider>);

    await act(async (): Promise<void> => {
      finish?.(response);
    });
    expect(screen.queryByText(REPORT_COPY.received)).not.toBeInTheDocument();
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
  });
});
