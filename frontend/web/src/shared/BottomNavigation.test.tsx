import {
  afterEach,
  beforeEach,
  describe,
  expect,
  it,
  jest,
} from '@jest/globals';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import BottomNavigation from './BottomNavigation';

const mockNavigate = jest.fn();
jest.mock('react-router', () => ({
  useNavigate: () => mockNavigate,
  useLocation: () => ({ pathname: '/' }),
}));
jest.mock('../assets/icons/home.svg', () => 'home.svg');
jest.mock('../assets/icons/four-cut-album.svg', () => 'four-cut-album.svg');
jest.mock('../assets/icons/settings.svg', () => 'settings.svg');
jest.mock('../assets/icons/kakao.svg', () => 'kakao.svg');
jest.mock(
  '../assets/icons/bottom-sheet-close.svg',
  () => 'bottom-sheet-close.svg',
);

beforeEach(() => {
  localStorage.removeItem('harudle.has-completed-oauth');
  mockNavigate.mockClear();
});
afterEach(() => {
  localStorage.removeItem('harudle.has-completed-oauth');
});

const prepareDialogs = (): void => {
  document.querySelectorAll('dialog').forEach((dialog) => {
    Object.defineProperties(dialog, {
      showModal: { value: () => dialog.setAttribute('open', '') },
      close: { value: () => dialog.removeAttribute('open') },
    });
  });
};

describe('하단 메뉴의 로그인 안내', () => {
  it.each(['네컷 모아보기', '설정'])(
    '비로그인 사용자가 %s을 누르면 이동 없이 안내하고 둘러보기로 닫는다',
    async (label) => {
      const user = userEvent.setup();
      render(<BottomNavigation />);
      prepareDialogs();
      await user.click(screen.getByRole('button', { name: label }));
      const dialog = screen.getByRole('dialog');
      expect(dialog).toHaveAttribute('open');
      expect(dialog).toHaveTextContent('이 기능을 이용하려면 로그인해 주세요.');
      expect(mockNavigate).not.toHaveBeenCalled();
      await user.click(screen.getByRole('button', { name: '계속 둘러보기' }));
      expect(dialog).not.toHaveAttribute('open');
      expect(mockNavigate).not.toHaveBeenCalled();
    },
  );

  it('안내 시트에서 로그인 버튼을 누르면 로그인 페이지로 이동한다', async () => {
    const user = userEvent.setup();
    render(<BottomNavigation />);
    prepareDialogs();
    await user.click(screen.getByRole('button', { name: '설정' }));
    const dialog = screen.getByRole('dialog');
    await user.click(screen.getByRole('button', { name: '카카오로 로그인' }));
    expect(dialog).not.toHaveAttribute('open');
    expect(mockNavigate).toHaveBeenCalledWith('/login');
  });

  it('홈은 비로그인 상태에서도 이동한다', async () => {
    const user = userEvent.setup();
    render(<BottomNavigation />);
    await user.click(screen.getByRole('button', { name: '홈' }));
    expect(mockNavigate).toHaveBeenCalledWith('/');
  });

  it.each([
    { label: '네컷 모아보기', path: '/album' },
    { label: '설정', path: '/setting' },
  ])('로그인 완료 표시가 있으면 $label로 이동한다', async ({ label, path }) => {
    localStorage.setItem('harudle.has-completed-oauth', 'true');
    const user = userEvent.setup();
    render(<BottomNavigation />);
    await user.click(screen.getByRole('button', { name: label }));
    expect(mockNavigate).toHaveBeenCalledWith(path);
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
  });
});
