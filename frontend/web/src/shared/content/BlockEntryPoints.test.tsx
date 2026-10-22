import { beforeEach, describe, expect, it, jest } from '@jest/globals';
import {
  fireEvent,
  render,
  screen,
  waitFor,
  within,
} from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import ContentOptionsButton from '../content/ContentOptionsButton';
import { setUserBlocked } from '../../domain/blocks/blocks';

jest.mock('../../domain/blocks/blocks', () => ({ setUserBlocked: jest.fn() }));
jest.mock('react-router', () => ({ useNavigate: () => jest.fn() }));
jest.mock('../../assets/icons/feed-more.svg', () => 'more.svg');
jest.mock('../../assets/icons/kakao.svg', () => 'kakao.svg');
jest.mock('../../assets/icons/bottom-sheet-close.svg', () => 'close.svg');
beforeEach((): void => {
  localStorage.clear();
  jest.mocked(setUserBlocked).mockReset();
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
const openConfirm = async (): Promise<void> => {
  const user = userEvent.setup();
  await user.click(screen.getByRole('button', { name: '피드 더보기' }));
  await user.click(screen.getByRole('button', { name: '작성자 차단' }));
};
describe('작성자 차단 진입', (): void => {
  it('비회원은 로그인 안내로 연결하고 요청하지 않는다', async (): Promise<void> => {
    render(
      <ContentOptionsButton
        authorId="author-1"
        authorNickname="네오"
        target={{ targetType: 'FEED', targetId: 'feed-1' }}
      />,
    );
    await openConfirm();
    expect(
      screen.getByText('작성자를 차단하려면 로그인이 필요해요.'),
    ).toBeVisible();
    expect(setUserBlocked).not.toHaveBeenCalled();
  });
  it('확인 취소는 차단하지 않고, 실패 시 대상을 유지하며 다시 제출한다', async (): Promise<void> => {
    localStorage.setItem('harudle.has-completed-oauth', 'true');
    jest
      .mocked(setUserBlocked)
      .mockRejectedValue(new Error('잠시 후 다시 시도해주세요.'));
    render(
      <ContentOptionsButton
        authorId="author-1"
        authorNickname="네오"
        target={{ targetType: 'FEED', targetId: 'feed-1' }}
      />,
    );
    await openConfirm();
    const dialog = screen
      .getByRole('heading', { name: '작성자 차단 확인' })
      .closest('dialog');
    if (!dialog) throw new Error('차단 확인 창이 없습니다.');
    expect(within(dialog).getByText('네오님을 차단할까요?')).toBeVisible();
    await userEvent.click(within(dialog).getByRole('button', { name: '취소' }));
    expect(dialog).not.toHaveAttribute('open');
    expect(setUserBlocked).not.toHaveBeenCalled();
    await openConfirm();
    await userEvent.click(
      within(dialog).getByRole('button', { name: '차단하기' }),
    );
    expect(await within(dialog).findByRole('alert')).toHaveTextContent(
      '잠시 후 다시 시도해주세요.',
    );
    await userEvent.click(
      within(dialog).getByRole('button', { name: '차단하기' }),
    );
    expect(setUserBlocked).toHaveBeenCalledTimes(2);
    expect(setUserBlocked).toHaveBeenLastCalledWith('author-1', true);
  });
  it('제출 중에는 중복 제출과 Esc·배경 닫힘을 막는다', async (): Promise<void> => {
    localStorage.setItem('harudle.has-completed-oauth', 'true');
    let rejectRequest: (error: Error) => void = (): void => undefined;
    jest.mocked(setUserBlocked).mockImplementation(
      (): Promise<void> =>
        new Promise((_resolve, reject): void => {
          rejectRequest = reject;
        }),
    );
    render(
      <ContentOptionsButton
        authorId="author-1"
        authorNickname="네오"
        target={{ targetType: 'FEED', targetId: 'feed-1' }}
      />,
    );
    await openConfirm();
    const dialog = screen
      .getByRole('heading', { name: '작성자 차단 확인' })
      .closest('dialog');
    if (!dialog) throw new Error('차단 확인 창이 없습니다.');
    await userEvent.click(
      within(dialog).getByRole('button', { name: '차단하기' }),
    );
    expect(
      within(dialog).getByRole('button', { name: '차단 중…' }),
    ).toBeDisabled();
    expect(
      fireEvent(
        dialog,
        new Event('cancel', { bubbles: true, cancelable: true }),
      ),
    ).toBe(false);
    fireEvent.click(dialog);
    expect(dialog).toHaveAttribute('open');
    expect(setUserBlocked).toHaveBeenCalledTimes(1);
    rejectRequest(new Error('재시도'));
    await waitFor((): void =>
      expect(
        within(dialog).getByRole('button', { name: '차단하기' }),
      ).toBeEnabled(),
    );
  });
});
