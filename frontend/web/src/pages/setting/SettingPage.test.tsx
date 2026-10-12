import { beforeEach, describe, expect, it, jest } from '@jest/globals';
import {
  act,
  fireEvent,
  render,
  screen,
  waitFor,
} from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import SettingPage from './SettingPage';
import { RequestError } from '../../shared/api';
import type {
  getUserProfile,
  updateUserNickname,
} from '../../domain/user/profile';

const mockGetProfile = jest.fn<typeof getUserProfile>();
const mockUpdateNickname = jest.fn<typeof updateUserNickname>();

jest.mock('../../domain/user/profile', () => ({
  getUserProfile: () => mockGetProfile(),
  updateUserNickname: (nickname: string) => mockUpdateNickname(nickname),
}));
jest.mock('../../shared/BottomNavigationLayout', () => ({
  __esModule: true,
  default: ({ children }: { children: React.ReactNode }) => children,
}));
jest.mock('./PwaInstallButton', () => ({
  __esModule: true,
  default: () => null,
}));
jest.mock('./useLogout', () => ({
  __esModule: true,
  default: () => ({ request: { status: 'idle' }, handleLogout: jest.fn() }),
}));
jest.mock('../../assets/images/loading-animation.webp', () => 'loading.webp');
jest.mock('../../assets/images/request-fail.png', () => 'request-fail.png');
jest.mock('../../assets/icons/call_made.svg', () => 'call.svg');
jest.mock('../../assets/icons/logout.svg', () => 'logout.svg');
jest.mock('../../assets/icons/article_person.svg', () => 'article.svg');
jest.mock('../../assets/images/profile-fallback.svg', () => 'fallback.svg');

const profile = {
  id: 'user-1',
  name: '정연준',
  nickname: '연준',
  profileImageUrl: '/profile.svg',
  email: null,
  role: 'USER' as const,
  oauthProviders: ['kakao' as const],
  createdAt: '2026-10-12T00:00:00Z',
};

beforeEach(() => {
  Object.defineProperty(globalThis, '__APP_VERSION__', {
    value: '1.1.0',
    configurable: true,
  });
  mockGetProfile.mockReset().mockResolvedValue(profile);
  mockUpdateNickname.mockReset().mockImplementation(async (nickname) => ({
    id: profile.id,
    nickname,
    profileImageUrl: profile.profileImageUrl,
  }));
});

const openNicknameModal = async () => {
  const user = userEvent.setup();
  render(<SettingPage />);
  await screen.findByText('연준');
  const dialog = screen.getByRole('dialog', { hidden: true });
  Object.defineProperties(dialog, {
    showModal: { value: () => dialog.setAttribute('open', '') },
    close: { value: () => dialog.removeAttribute('open') },
  });
  await user.click(screen.getByRole('button', { name: '수정' }));
  return { user, dialog, input: screen.getByLabelText('닉네임') };
};

describe('설정 프로필과 닉네임 수정', () => {
  it('이름과 닉네임을 구분하고 입력 그대로 전송한 뒤 서버 결과를 표시한다', async () => {
    mockUpdateNickname.mockResolvedValueOnce({
      id: profile.id,
      nickname: '새닉네임',
      profileImageUrl: profile.profileImageUrl,
    });
    const { user, dialog, input } = await openNicknameModal();
    expect(screen.getByText('정연준')).toBeInTheDocument();
    expect(input).toHaveValue('연준');
    await user.clear(input);
    await user.type(input, ' 새닉네임 ');
    await user.click(screen.getByRole('button', { name: '저장' }));
    await waitFor(() => expect(dialog).not.toHaveAttribute('open'));
    expect(mockUpdateNickname).toHaveBeenCalledWith(' 새닉네임 ');
    expect(screen.getByText('새닉네임')).toBeInTheDocument();
    expect(screen.getByText('정연준')).toBeInTheDocument();
  });

  it('중복 오류를 표시하고 닉네임을 바꿔 다시 저장할 수 있다', async () => {
    mockUpdateNickname.mockRejectedValueOnce(
      new RequestError({
        type: 'about:blank',
        title: 'Conflict',
        status: 409,
        detail: '서버 상세 문구',
        instance: '/api/v1/me',
        code: 'NICKNAME_ALREADY_USED',
      }),
    );
    const { user, dialog, input } = await openNicknameModal();
    await user.clear(input);
    await user.type(input, '네오');
    await user.click(screen.getByRole('button', { name: '저장' }));
    expect(
      await screen.findByText('이미 사용 중인 닉네임이에요.'),
    ).toBeInTheDocument();
    expect(dialog).toHaveAttribute('open');
    expect(screen.getByText('연준')).toBeInTheDocument();
    await user.clear(input);
    await user.type(input, '새닉네임');
    await user.click(screen.getByRole('button', { name: '저장' }));
    await waitFor(() => expect(dialog).not.toHaveAttribute('open'));
  });

  it('취소 후 다시 열면 기존 닉네임으로 초기화한다', async () => {
    const { user, dialog, input } = await openNicknameModal();
    await user.clear(input);
    await user.type(input, '취소할값');
    await user.click(screen.getByRole('button', { name: '취소' }));
    expect(dialog).not.toHaveAttribute('open');
    expect(mockUpdateNickname).not.toHaveBeenCalled();
    await user.click(screen.getByRole('button', { name: '수정' }));
    expect(input).toHaveValue('연준');
  });

  it('공백은 거부하고 입력을 Unicode 10자로 제한하며 글자 수를 표시한다', async () => {
    const { user, input } = await openNicknameModal();
    expect(screen.getByText('2 / 10')).toBeInTheDocument();
    fireEvent.change(input, { target: { value: '   ' } });
    await user.click(screen.getByRole('button', { name: '저장' }));
    expect(
      screen.getByText('닉네임은 1자 이상 10자 이하로 입력해 주세요.'),
    ).toBeInTheDocument();
    expect(mockUpdateNickname).not.toHaveBeenCalled();

    fireEvent.change(input, { target: { value: '가'.repeat(11) } });
    expect(input).toHaveValue('가'.repeat(10));
    expect(screen.getByText('10 / 10')).toBeInTheDocument();
    fireEvent.change(input, { target: { value: '😀'.repeat(11) } });
    expect(input).toHaveValue('😀'.repeat(10));
    await user.click(screen.getByRole('button', { name: '저장' }));
    await waitFor(() =>
      expect(mockUpdateNickname).toHaveBeenCalledWith('😀'.repeat(10)),
    );
  });

  it('기존 닉네임이 10자를 넘어도 변경 없는 저장은 허용한다', async () => {
    const nickname = ' 기존회원의아주긴닉네임 ';
    mockGetProfile.mockResolvedValueOnce({ ...profile, nickname });
    const user = userEvent.setup();
    render(<SettingPage />);
    await screen.findByText(nickname.trim());
    const dialog = screen.getByRole('dialog', { hidden: true });
    Object.defineProperties(dialog, {
      showModal: { value: () => dialog.setAttribute('open', '') },
      close: { value: () => dialog.removeAttribute('open') },
    });
    await user.click(screen.getByRole('button', { name: '수정' }));
    await user.click(screen.getByRole('button', { name: '저장' }));
    await waitFor(() =>
      expect(mockUpdateNickname).toHaveBeenCalledWith(nickname),
    );
  });
  it('저장 중 버튼 제출은 막고 배경으로 닫은 뒤 재열어도 진행 상태를 유지한다', async () => {
    let completeSave:
      | ((profile: Awaited<ReturnType<typeof updateUserNickname>>) => void)
      | undefined;
    mockUpdateNickname.mockImplementationOnce(
      () =>
        new Promise((resolve) => {
          completeSave = resolve;
        }),
    );
    const { user, dialog, input } = await openNicknameModal();
    await user.clear(input);
    await user.type(input, '새닉네임');
    await user.click(screen.getByRole('button', { name: '저장' }));

    expect(input).toBeDisabled();
    expect(screen.getByRole('button', { name: '저장 중...' })).toBeDisabled();
    expect(screen.getByRole('button', { name: '취소' })).toBeDisabled();
    const form = input.closest('form');
    if (!form) throw new Error('닉네임 입력 폼을 찾을 수 없습니다.');
    fireEvent.submit(form);
    expect(mockUpdateNickname).toHaveBeenCalledTimes(1);

    await user.click(screen.getByRole('button', { name: '취소' }));
    expect(dialog).toHaveAttribute('open');
    expect(fireEvent(dialog, new Event('cancel', { cancelable: true }))).toBe(
      true,
    );
    await user.click(dialog);
    expect(dialog).not.toHaveAttribute('open');
    await user.click(screen.getByRole('button', { name: '수정' }));
    expect(dialog).toHaveAttribute('open');
    expect(input).toHaveValue('새닉네임');
    expect(input).toBeDisabled();
    expect(screen.getByRole('button', { name: '저장 중...' })).toBeDisabled();
    fireEvent.submit(form);
    expect(mockUpdateNickname).toHaveBeenCalledTimes(1);

    await act(async () => {
      completeSave?.({
        id: profile.id,
        nickname: '새닉네임',
        profileImageUrl: profile.profileImageUrl,
      });
    });
    await waitFor(() => expect(dialog).not.toHaveAttribute('open'));
    expect(screen.getByText('새닉네임')).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: '수정' }));
    expect(input).toHaveValue('새닉네임');
    expect(fireEvent(dialog, new Event('cancel', { cancelable: true }))).toBe(
      true,
    );
    await user.click(dialog);
    expect(dialog).not.toHaveAttribute('open');
  });

  it('저장이 실패하면 입력과 취소를 다시 허용하고 재열기 시 오류를 초기화한다', async () => {
    let failSave: ((error: Error) => void) | undefined;
    mockUpdateNickname.mockImplementationOnce(
      () =>
        new Promise((_, reject) => {
          failSave = reject;
        }),
    );
    const { user, dialog, input } = await openNicknameModal();
    await user.clear(input);
    await user.type(input, '새닉네임');
    await user.click(screen.getByRole('button', { name: '저장' }));
    await act(async () => {
      failSave?.(new Error('Network error'));
    });
    expect(
      await screen.findByText(
        '닉네임을 수정하지 못했어요. 다시 시도해 주세요.',
      ),
    ).toBeInTheDocument();
    expect(input).not.toBeDisabled();
    expect(screen.getByRole('button', { name: '취소' })).not.toBeDisabled();
    await user.click(screen.getByRole('button', { name: '취소' }));
    expect(dialog).not.toHaveAttribute('open');
    await user.click(screen.getByRole('button', { name: '수정' }));
    expect(input).toHaveValue('연준');
    expect(
      screen.queryByText('닉네임을 수정하지 못했어요. 다시 시도해 주세요.'),
    ).not.toBeInTheDocument();
  });
});
