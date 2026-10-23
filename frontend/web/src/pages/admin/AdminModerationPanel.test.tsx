import { beforeEach, describe, expect, it, jest } from '@jest/globals';
import {
  act,
  fireEvent,
  render,
  screen,
  waitFor,
  within,
} from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import AdminModerationPanel from './AdminModerationPanel';
import {
  getAdminModeration,
  getAdminModerationHistory,
  setAdminModeration,
  type ModerationState,
} from './adminModerationApi';
import { ToastProvider } from '../../shared/ToastContext';
import { RequestError } from '../../shared/api';
jest.mock('./adminModerationApi', () => ({
  ...jest.requireActual<typeof import('./adminModerationApi')>(
    './adminModerationApi',
  ),
  getAdminModeration: jest.fn(),
  getAdminModerationHistory: jest.fn(),
  setAdminModeration: jest.fn(),
}));
jest.mock('../../assets/images/loading-animation.webp', () => 'loading.webp');
jest.mock('../../assets/icons/toast-success.svg', () => 'success.svg');
jest.mock('../../assets/icons/toast-error.svg', () => 'error.svg');
jest.mock('../../assets/icons/toast-loading.svg', () => 'loading.svg');
const state: ModerationState = {
  targetType: 'USER',
  targetId: 'user-1',
  restricted: false,
  version: 0,
  reason: null,
  actorId: null,
  changedAt: null,
};
const history = {
  content: [],
  page: 0,
  size: 20,
  totalElements: 0,
  totalPages: 0,
  hasNext: false,
};
const changed = {
  ...state,
  restricted: true,
  version: 1,
  reason: '반복 위반',
  actorId: 'admin-1',
  changedAt: '2026-10-23T03:00:00Z',
};
beforeEach((): void => {
  jest.mocked(getAdminModeration).mockReset().mockResolvedValue(state);
  jest.mocked(getAdminModerationHistory).mockReset().mockResolvedValue(history);
  jest.mocked(setAdminModeration).mockReset();
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
const openAction = async (): Promise<HTMLDialogElement> => {
  await userEvent.click(
    await screen.findByRole('button', { name: '사용자 정지' }),
  );
  const dialog = screen
    .getByRole('heading', { name: '사용자 정지' })
    .closest('dialog');
  if (!dialog) throw new Error('조치 확인창이 없습니다.');
  return dialog;
};
describe('관리자 조치 확인', (): void => {
  it('사유 없는 전송을 막고 실패 시 내용을 유지하며 대기 중 닫기·중복 제출을 막는다', async (): Promise<void> => {
    let finish: (value: ModerationState) => void = (): void => undefined;
    jest
      .mocked(setAdminModeration)
      .mockRejectedValueOnce(new Error('서버 연결 실패'))
      .mockImplementationOnce(
        (): Promise<ModerationState> =>
          new Promise((resolve): void => {
            finish = resolve;
          }),
      );
    render(<AdminModerationPanel targetType="USER" targetId="user-1" />, {
      wrapper: ToastProvider,
    });
    const dialog = await openAction();
    const form = within(dialog);
    expect(form.getByRole('button', { name: '확인하기' })).toBeDisabled();
    await userEvent.type(
      form.getByRole('textbox', { name: '처리 사유' }),
      '반복 위반',
    );
    await userEvent.click(form.getByRole('button', { name: '확인하기' }));
    expect(await form.findByRole('alert')).toHaveTextContent('서버 연결 실패');
    expect(form.getByRole('textbox')).toHaveValue('반복 위반');
    await userEvent.click(form.getByRole('button', { name: '확인하기' }));
    expect(form.getByRole('button', { name: '처리 중…' })).toBeDisabled();
    expect(
      fireEvent(
        dialog,
        new Event('cancel', { bubbles: true, cancelable: true }),
      ),
    ).toBe(false);
    expect(form.getByRole('button', { name: '취소' })).toBeDisabled();
    expect(setAdminModeration).toHaveBeenCalledTimes(2);
    jest.mocked(getAdminModeration).mockResolvedValue(changed);
    await act(async (): Promise<void> => finish(changed));
    expect(await form.findByRole('status')).toHaveTextContent('처리가 완료');
    expect(setAdminModeration).toHaveBeenLastCalledWith(
      'USER',
      'user-1',
      true,
      0,
      '반복 위반',
    );
  });
  it('409면 변경을 다시 보내지 않고 명시적 새로고침 이후 다시 확인한다', async (): Promise<void> => {
    jest.mocked(setAdminModeration).mockRejectedValueOnce(
      new RequestError({
        type: 'urn:conflict',
        title: 'Conflict',
        status: 409,
        code: 'MODERATION_CONFLICT',
        detail: '버전 충돌',
        instance: '/moderation',
      }),
    );
    render(<AdminModerationPanel targetType="USER" targetId="user-1" />, {
      wrapper: ToastProvider,
    });
    const dialog = await openAction();
    const form = within(dialog);
    await userEvent.type(form.getByRole('textbox'), '반복 위반');
    await userEvent.click(form.getByRole('button', { name: '확인하기' }));
    expect(await form.findByRole('alert')).toHaveTextContent(
      '다른 관리자가 변경',
    );
    expect(
      form.queryByRole('button', { name: '확인하기' }),
    ).not.toBeInTheDocument();
    await userEvent.click(form.getByRole('button', { name: '닫기' }));
    expect(screen.getByRole('button', { name: '사용자 정지' })).toBeDisabled();
    expect(getAdminModeration).toHaveBeenCalledTimes(1);
    expect(setAdminModeration).toHaveBeenCalledTimes(1);
    await userEvent.click(
      screen.getByRole('button', { name: '다시 불러오기' }),
    );
    await waitFor((): void =>
      expect(screen.getByRole('button', { name: '사용자 정지' })).toBeEnabled(),
    );
    expect(setAdminModeration).toHaveBeenCalledTimes(1);
  });
  it('변경 성공 뒤 갱신 실패도 성공 상태를 유지하고 GET만 재시도한다', async (): Promise<void> => {
    jest.mocked(setAdminModeration).mockResolvedValueOnce(changed);
    jest
      .mocked(getAdminModeration)
      .mockResolvedValueOnce(state)
      .mockRejectedValueOnce(new Error('갱신 실패'))
      .mockResolvedValueOnce(changed);
    render(<AdminModerationPanel targetType="USER" targetId="user-1" />, {
      wrapper: ToastProvider,
    });
    const dialog = await openAction();
    const form = within(dialog);
    await userEvent.type(form.getByRole('textbox'), '반복 위반');
    await userEvent.click(form.getByRole('button', { name: '확인하기' }));
    expect(await form.findByRole('status')).toHaveTextContent('처리가 완료');
    await userEvent.click(form.getByRole('button', { name: '닫기' }));
    expect(screen.getByText('정지 중')).toBeVisible();
    expect(screen.getByRole('alert')).toHaveTextContent(
      '최신 정보를 불러오지 못했어요',
    );
    await userEvent.click(
      screen.getByRole('button', { name: '다시 불러오기' }),
    );
    await waitFor((): void =>
      expect(screen.queryByRole('alert')).not.toBeInTheDocument(),
    );
    expect(setAdminModeration).toHaveBeenCalledTimes(1);
    expect(getAdminModeration).toHaveBeenCalledTimes(3);
  });
  it('삭제된 대상의 이력은 읽되 조치를 막고 화면 이탈 때 조회를 취소한다', async (): Promise<void> => {
    const { unmount } = render(
      <AdminModerationPanel targetType="USER" targetId="user-1" />,
      { wrapper: ToastProvider },
    );
    await screen.findByRole('button', { name: '사용자 정지' });
    const signal = jest.mocked(getAdminModeration).mock.calls[0][2];
    unmount();
    expect(signal?.aborted).toBe(true);
    render(
      <AdminModerationPanel
        targetType="USER"
        targetId="deleted-user"
        available={false}
      />,
      { wrapper: ToastProvider },
    );
    expect(
      await screen.findByRole('button', { name: '사용자 정지' }),
    ).toBeDisabled();
    expect(getAdminModeration).toHaveBeenCalledTimes(2);
    expect(getAdminModerationHistory).toHaveBeenCalledTimes(2);
  });
  it('타깃을 바꾼 뒤 끝난 이전 조치가 새 사용자 상태와 알림을 덮지 않는다', async (): Promise<void> => {
    let finish: (value: ModerationState) => void = (): void => undefined;
    jest.mocked(setAdminModeration).mockImplementationOnce(
      (): Promise<ModerationState> =>
        new Promise((resolve): void => {
          finish = resolve;
        }),
    );
    const { rerender } = render(
      <AdminModerationPanel key="user-1" targetType="USER" targetId="user-1" />,
      { wrapper: ToastProvider },
    );
    const dialog = await openAction();
    await userEvent.type(
      within(dialog).getByRole('textbox'),
      '이전 사용자 위반',
    );
    await userEvent.click(
      within(dialog).getByRole('button', { name: '확인하기' }),
    );
    jest
      .mocked(getAdminModeration)
      .mockResolvedValue({ ...state, targetId: 'user-2' });
    rerender(
      <AdminModerationPanel key="user-2" targetType="USER" targetId="user-2" />,
    );
    await screen.findByRole('button', { name: '사용자 정지' });
    await act(async (): Promise<void> => finish(changed));
    expect(screen.getByText('정지 없음')).toBeVisible();
    expect(screen.queryByText('정지 중')).not.toBeInTheDocument();
    expect(screen.queryByText('처리가 완료되었어요.')).not.toBeInTheDocument();
    expect(getAdminModeration).toHaveBeenCalledTimes(2);
  });
  it('이력을 불러오는 동안 반복 클릭과 변경을 잠그고 늦은 응답을 취소한다', async (): Promise<void> => {
    let finish: (value: typeof history) => void = (): void => undefined;
    jest
      .mocked(getAdminModerationHistory)
      .mockResolvedValueOnce({
        ...history,
        hasNext: true,
        totalPages: 2,
        totalElements: 21,
      })
      .mockImplementationOnce(
        (): Promise<typeof history> =>
          new Promise((resolve): void => {
            finish = resolve;
          }),
      );
    const { unmount } = render(
      <AdminModerationPanel targetType="USER" targetId="user-1" />,
      { wrapper: ToastProvider },
    );
    const next = await screen.findByRole('button', { name: '다음 이력' });
    await userEvent.click(next);
    expect(next).toBeDisabled();
    expect(screen.getByRole('button', { name: '사용자 정지' })).toBeDisabled();
    expect(getAdminModerationHistory).toHaveBeenCalledTimes(2);
    const signal = jest.mocked(getAdminModerationHistory).mock.calls[1][3];
    unmount();
    expect(signal?.aborted).toBe(true);
    await act(async (): Promise<void> => finish(history));
  });
  it('처리자가 물리 삭제되어도 조치 사유·시각·버전과 목록을 보여준다', async (): Promise<void> => {
    jest.mocked(getAdminModerationHistory).mockResolvedValueOnce({
      ...history,
      content: [{ ...changed, id: 'event-1', actorId: null }],
      totalElements: 1,
      totalPages: 1,
    });
    render(<AdminModerationPanel targetType="USER" targetId="user-1" />, {
      wrapper: ToastProvider,
    });
    expect(
      await screen.findByText('처리자 삭제된 관리자 · 버전 1'),
    ).toBeVisible();
    expect(screen.getByText('반복 위반')).toBeVisible();
    expect(screen.getByRole('button', { name: '사용자 정지' })).toBeEnabled();
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
  });
});
