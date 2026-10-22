import { beforeEach, describe, expect, it, jest } from '@jest/globals';
import { act, fireEvent, render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { createRef } from 'react';
import * as reports from '../../domain/reports/reportSubmission';
import ReportDialog, { type ReportDialogHandle } from './ReportDialog';
import { REPORT_COPY } from './copy';

const target: reports.ReportTarget = {
  targetType: 'FEED',
  targetId: '00000000-0000-4000-8000-000000000002',
};
const receipt: reports.ReportReceipt = {
  id: '10000000-0000-4000-8000-000000000001',
  status: 'RECEIVED',
  duplicate: false,
};

const openReport = (): ReportDialogHandle => {
  const ref = createRef<ReportDialogHandle>();
  render(<ReportDialog ref={ref} />);
  document.querySelectorAll('dialog').forEach((dialog): void => {
    Object.defineProperties(dialog, {
      showModal: {
        value: jest.fn((): void => dialog.setAttribute('open', '')),
      },
      close: { value: (): void => dialog.removeAttribute('open') },
    });
  });
  if (!ref.current) throw new Error('신고창이 없습니다.');
  const handle = ref.current;
  act((): void => handle.open(target));
  return handle;
};

beforeEach((): void => {
  jest.restoreAllMocks();
});

describe('신고 입력과 접수 결과', (): void => {
  it('처음 열기 전에는 신고 폼과 사유 및 제출 버튼을 만들지 않는다', (): void => {
    render(<ReportDialog ref={createRef<ReportDialogHandle>()} />);

    expect(document.querySelector('form')).toBeNull();
    expect(document.querySelector('input[type="radio"]')).toBeNull();
    expect(document.querySelector('footer')).toBeNull();
  });

  it('사유를 선택하고 접수하는 동안 중복 제출과 대상 교체를 막는다', async (): Promise<void> => {
    const user = userEvent.setup();
    let finish: ((value: reports.ReportReceipt) => void) | undefined;
    const submit = jest.spyOn(reports, 'submitReport').mockImplementation(
      (): Promise<reports.ReportReceipt> =>
        new Promise((resolve): void => {
          finish = resolve;
        }),
    );
    const handle = openReport();
    expect(
      screen.getByRole('button', { name: REPORT_COPY.submit }),
    ).toBeDisabled();
    await user.click(screen.getByRole('radio', { name: '스팸·광고' }));
    await user.type(
      screen.getByRole('textbox', { name: REPORT_COPY.description }),
      '반복 광고예요.',
    );
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
    expect(dialog).toHaveAttribute('open');
    const form = screen.getByRole('textbox').closest('form');
    if (!form) throw new Error('신고 입력 폼이 없습니다.');
    fireEvent.submit(form);
    act((): void =>
      handle.open({
        targetType: 'USER',
        targetId: '20000000-0000-4000-8000-000000000001',
      }),
    );
    expect(submit).toHaveBeenCalledTimes(1);
    expect(dialog.showModal).toHaveBeenCalledTimes(1);
    expect(submit).toHaveBeenCalledWith({
      ...target,
      reason: 'SPAM',
      description: '반복 광고예요.',
    });
    expect(
      screen.getByRole('heading', { name: '피드 신고' }),
    ).toBeInTheDocument();
    await act(async (): Promise<void> => {
      finish?.(receipt);
    });
    expect(screen.getByRole('status')).toHaveTextContent(REPORT_COPY.received);
    await user.click(screen.getByRole('button', { name: REPORT_COPY.close }));
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();

    submit.mockResolvedValueOnce(receipt);
    act((): void =>
      handle.open({
        targetType: 'USER',
        targetId: '20000000-0000-4000-8000-000000000001',
      }),
    );
    expect(
      screen.getByRole('heading', { name: '작성자 신고' }),
    ).toBeInTheDocument();
    expect(screen.getByRole('textbox')).toHaveValue('');
    await user.click(screen.getByRole('radio', { name: '기타' }));
    await user.click(screen.getByRole('button', { name: REPORT_COPY.submit }));
    expect(await screen.findByRole('status')).toHaveTextContent(
      REPORT_COPY.received,
    );
    expect(submit).toHaveBeenLastCalledWith({
      targetType: 'USER',
      targetId: '20000000-0000-4000-8000-000000000001',
      reason: 'OTHER',
      description: '',
    });
  });

  it('실패해도 입력을 유지하고 재시도 후 이미 접수된 신고임을 알린다', async (): Promise<void> => {
    const user = userEvent.setup();
    const submit = jest
      .spyOn(reports, 'submitReport')
      .mockRejectedValueOnce(new Error('다시 시도해 주세요.'))
      .mockResolvedValueOnce({ ...receipt, duplicate: true });
    openReport();
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
    expect(await screen.findByRole('status')).toHaveTextContent(
      REPORT_COPY.duplicate,
    );
    expect(submit).toHaveBeenNthCalledWith(2, {
      ...target,
      reason: 'HARASSMENT',
      description: '댓글에서 욕설을 반복해요.',
    });
  });

  it('상세 내용은 emoji도 한 글자로 세고 500자를 넘으면 제출을 막는다', async (): Promise<void> => {
    const user = userEvent.setup();
    const submit = jest.spyOn(reports, 'submitReport');
    openReport();
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
});
