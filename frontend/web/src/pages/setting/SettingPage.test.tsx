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
  updateUserProfile,
} from '../../domain/user/profile';

const mockGetProfile = jest.fn<typeof getUserProfile>();
const mockUpdateProfile = jest.fn<typeof updateUserProfile>();
const mockAddToast = jest.fn();

jest.mock('../../domain/user/profile', () => ({
  ...jest.requireActual<typeof import('../../domain/user/profile')>(
    '../../domain/user/profile',
  ),
  getUserProfile: () => mockGetProfile(),
  updateUserProfile: (...args: Parameters<typeof updateUserProfile>) =>
    mockUpdateProfile(...args),
}));
jest.mock('../../shared/ToastContext', () => ({
  useToastContext: () => ({ addToast: mockAddToast }),
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
  profileImageUrl: '/profile-images/v1/5.png',
  email: null,
  role: 'USER' as const,
  oauthProviders: ['kakao' as const],
  createdAt: '2026-10-12T00:00:00Z',
};

beforeEach((): void => {
  Object.defineProperty(globalThis, '__APP_VERSION__', {
    value: '1.1.0',
    configurable: true,
  });
  mockGetProfile.mockReset().mockResolvedValue(profile);
  mockUpdateProfile.mockReset().mockResolvedValue(undefined);
  mockAddToast.mockReset();
});

const openProfileModal = async () => {
  const user = userEvent.setup({ applyAccept: false });
  render(<SettingPage />);
  await screen.findByText('연준');
  const dialog = screen.getByRole('dialog', { hidden: true });
  Object.defineProperties(dialog, {
    showModal: { value: () => dialog.setAttribute('open', '') },
    close: { value: () => dialog.removeAttribute('open') },
  });
  await user.click(screen.getByRole('button', { name: '프로필 수정' }));
  return { user, dialog, input: screen.getByLabelText('닉네임') };
};

const problem = (
  status: 400 | 409 | 413 | 415 | 503,
  code: string,
): RequestError =>
  new RequestError({
    type: 'about:blank',
    title: 'Error',
    status,
    code,
    detail: '서버 오류',
    instance: '/api/v1/me/profile',
  });

describe('통합 프로필 수정', (): void => {
  it('사진과 닉네임을 한 번 저장하고 조회한 프로필을 반영한 뒤 완료를 알린다', async (): Promise<void> => {
    const { user, dialog, input } = await openProfileModal();
    expect(screen.getByRole('button', { name: '저장' })).toBeDisabled();
    await user.clear(input);
    await user.type(input, '새닉네임');
    await user.click(screen.getByRole('button', { name: '박쥐 프로필 사진' }));
    mockGetProfile.mockResolvedValueOnce({
      ...profile,
      nickname: '새닉네임',
      profileImageUrl: '/profile-images/v1/8.png',
    });
    await user.click(screen.getByRole('button', { name: '저장' }));
    await waitFor(() => expect(dialog).not.toHaveAttribute('open'));
    expect(mockUpdateProfile).toHaveBeenCalledTimes(1);
    expect(mockUpdateProfile).toHaveBeenCalledWith({
      nickname: '새닉네임',
      image: { type: 'default', code: 8 },
    });
    expect(screen.getByText('새닉네임')).toBeInTheDocument();
    expect(mockAddToast).toHaveBeenCalledWith(
      { type: 'success', message: '프로필을 수정했어요.' },
      2000,
    );
  });

  it('닉네임만 바꾸면 사진은 보내지 않고, 취소하면 서버 값을 다시 채운다', async (): Promise<void> => {
    const { user, dialog, input } = await openProfileModal();
    await user.type(input, '취소');
    await user.click(screen.getByRole('button', { name: '취소' }));
    expect(mockUpdateProfile).not.toHaveBeenCalled();
    await user.click(screen.getByRole('button', { name: '프로필 사진 수정' }));
    expect(input).toHaveValue('연준');
    await user.clear(input);
    await user.type(input, '새이름');
    await user.click(screen.getByRole('button', { name: '저장' }));
    await waitFor(() => expect(dialog).not.toHaveAttribute('open'));
    expect(mockUpdateProfile).toHaveBeenCalledWith({ nickname: '새이름' });
  });

  it('닉네임 중복은 입력 아래에 표시하고 사진 선택을 유지한다', async (): Promise<void> => {
    mockUpdateProfile.mockRejectedValueOnce(
      problem(409, 'NICKNAME_ALREADY_IN_USE'),
    );
    const { user, dialog, input } = await openProfileModal();
    await user.clear(input);
    await user.type(input, '네오');
    await user.click(screen.getByRole('button', { name: '박쥐 프로필 사진' }));
    await user.click(screen.getByRole('button', { name: '저장' }));
    expect(
      await screen.findByText('이미 사용 중인 닉네임이에요.'),
    ).toBeInTheDocument();
    expect(input).toHaveAttribute('aria-invalid', 'true');
    expect(dialog).toHaveAttribute('open');
    expect(
      screen.getByRole('button', { name: '박쥐 프로필 사진' }),
    ).toHaveAttribute('aria-pressed', 'true');
    expect(mockAddToast).not.toHaveBeenCalled();
    await user.clear(input);
    await user.type(input, '새닉네임');
    expect(
      screen.queryByText('이미 사용 중인 닉네임이에요.'),
    ).not.toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: '저장' }));
    await waitFor(() => expect(dialog).not.toHaveAttribute('open'));
  });

  it('빈 닉네임과 한글 조합 중 제출을 막고 조합이 끝난 뒤 10자로 제한한다', async (): Promise<void> => {
    const { user, input } = await openProfileModal();
    fireEvent.change(input, { target: { value: '   ' } });
    await user.click(screen.getByRole('button', { name: '저장' }));
    expect(
      screen.getByText('닉네임은 1자 이상 10자 이하로 입력해 주세요.'),
    ).toBeInTheDocument();
    fireEvent.compositionStart(input);
    fireEvent.change(input, { target: { value: '가'.repeat(11) } });
    const form = input.closest('form');
    if (!form) throw new Error('프로필 폼을 찾을 수 없습니다.');
    fireEvent.submit(form);
    expect(input).toHaveValue('가'.repeat(11));
    expect(mockUpdateProfile).not.toHaveBeenCalled();
    fireEvent.compositionEnd(input);
    expect(input).toHaveValue('가'.repeat(10));
  });

  it.each([
    [
      new File(['gif'], 'photo.gif', { type: 'image/gif' }),
      'JPEG, PNG 사진을 선택해 주세요.',
    ],
    [
      new File([new Uint8Array(5 * 1024 * 1024 + 1)], 'photo.png', {
        type: 'image/png',
      }),
      '사진은 5MiB까지 올릴 수 있어요.',
    ],
  ])(
    '잘못된 파일은 기본 안내 대신 오류만 보여주고 기본 사진으로 복구할 수 있다',
    async (file, message): Promise<void> => {
      const { user } = await openProfileModal();
      await user.upload(screen.getByLabelText('프로필 사진 파일'), file);
      expect(screen.getByText(message)).toBeInTheDocument();
      expect(
        screen.queryByText('JPEG, PNG 파일을 5MiB까지 올릴 수 있어요.'),
      ).not.toBeInTheDocument();
      expect(screen.getByRole('button', { name: '저장' })).toBeDisabled();
      await user.click(
        screen.getByRole('button', { name: '박쥐 프로필 사진' }),
      );
      expect(screen.queryByText(message)).not.toBeInTheDocument();
      expect(screen.getByRole('button', { name: '저장' })).toBeEnabled();
    },
  );

  it('개인 사진을 미리 보여주고 선택한 파일만 저장 요청에 담는다', async (): Promise<void> => {
    const { user, dialog } = await openProfileModal();
    const file = new File(['png'], 'photo.png', { type: 'image/png' });
    await user.upload(screen.getByLabelText('프로필 사진 파일'), file);
    await waitFor(() =>
      expect(screen.getByAltText('선택한 프로필 사진')).toHaveAttribute(
        'src',
        'data:image/png;base64,cG5n',
      ),
    );
    await user.click(screen.getByRole('button', { name: '저장' }));
    await waitFor(() => expect(dialog).not.toHaveAttribute('open'));
    expect(mockUpdateProfile).toHaveBeenCalledWith({
      image: { type: 'upload', file },
    });
  });

  it('저장 뒤 조회만 실패하면 입력을 잠그고 저장을 반복하지 않고 조회만 재시도한다', async (): Promise<void> => {
    const { user, dialog, input } = await openProfileModal();
    await user.click(screen.getByRole('button', { name: '박쥐 프로필 사진' }));
    mockGetProfile.mockRejectedValueOnce(new Error('조회 실패'));
    await user.click(screen.getByRole('button', { name: '저장' }));
    expect(
      await screen.findByText('수정한 프로필을 불러오지 못했어요.'),
    ).toBeInTheDocument();
    expect(input).toBeDisabled();
    expect(mockAddToast).not.toHaveBeenCalled();
    await user.click(screen.getByRole('button', { name: '다시 불러오기' }));
    await waitFor(() => expect(dialog).not.toHaveAttribute('open'));
    expect(mockUpdateProfile).toHaveBeenCalledTimes(1);
    expect(mockGetProfile).toHaveBeenCalledTimes(3);
    expect(mockAddToast).toHaveBeenCalledTimes(1);
  });

  it('응답 유실 뒤 조회에 성공해도 저장 완료로 안내하지 않는다', async (): Promise<void> => {
    const { ProfileSaveResultUnknownError } =
      await import('../../domain/user/profile');
    mockUpdateProfile.mockRejectedValueOnce(
      new ProfileSaveResultUnknownError(),
    );
    const { user, dialog } = await openProfileModal();
    await user.click(screen.getByRole('button', { name: '박쥐 프로필 사진' }));
    await user.click(screen.getByRole('button', { name: '저장' }));
    expect(
      await screen.findByText(
        '저장 결과를 확인하지 못했어요. 다시 불러와 주세요.',
      ),
    ).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: '닫기' }));
    await user.click(screen.getByRole('button', { name: '프로필 수정' }));
    expect(screen.getByRole('button', { name: '다시 불러오기' })).toBeEnabled();
    await user.click(screen.getByRole('button', { name: '다시 불러오기' }));
    await waitFor(() => expect(dialog).not.toHaveAttribute('open'));
    expect(mockUpdateProfile).toHaveBeenCalledTimes(1);
    expect(mockAddToast).not.toHaveBeenCalled();
  });

  it('저장 중 창을 닫아도 중복 저장을 막고, 확인된 실패는 본문과 설명이 나뉜 토스트로 알린다', async (): Promise<void> => {
    let failSave: ((error: Error) => void) | undefined;
    mockUpdateProfile.mockImplementationOnce(
      () =>
        new Promise((_, reject) => {
          failSave = reject;
        }),
    );
    const { user, dialog, input } = await openProfileModal();
    await user.click(screen.getByRole('button', { name: '박쥐 프로필 사진' }));
    await user.click(screen.getByRole('button', { name: '저장' }));
    const form = input.closest('form');
    if (!form) throw new Error('프로필 폼을 찾을 수 없습니다.');
    fireEvent.submit(form);
    expect(mockUpdateProfile).toHaveBeenCalledTimes(1);
    expect(input).toBeDisabled();
    await user.click(dialog);
    expect(dialog).not.toHaveAttribute('open');
    expect(
      screen.getByRole('button', { name: '프로필 사진 수정' }),
    ).toBeDisabled();
    expect(
      screen.queryByText('프로필을 저장하고 있어요. 잠시만 기다려 주세요.'),
    ).not.toBeInTheDocument();
    await act(async (): Promise<void> => {
      failSave?.(problem(503, 'PROFILE_IMAGE_STORAGE_ERROR'));
    });
    expect(mockAddToast).toHaveBeenCalledWith(
      {
        type: 'error',
        message: '프로필을 저장하지 못했어요.',
        description: '다시 수정해 주세요.',
      },
      2000,
    );
    expect(screen.getByRole('button', { name: '프로필 수정' })).toBeEnabled();
  });

  it('이전 사진 주소 재조회가 늦게 끝나도 새로 저장한 프로필을 덮어쓰지 않는다', async (): Promise<void> => {
    const { user, dialog } = await openProfileModal();
    let finishOldRead: ((value: typeof profile) => void) | undefined;
    mockGetProfile.mockImplementationOnce(
      () =>
        new Promise((resolve) => {
          finishOldRead = resolve;
        }),
    );
    const avatar = screen
      .getByRole('button', { name: '프로필 사진 수정' })
      .querySelector('img');
    if (!avatar) throw new Error('현재 프로필 사진을 찾을 수 없습니다.');
    fireEvent.error(avatar);
    mockGetProfile.mockResolvedValueOnce({
      ...profile,
      nickname: '새프로필',
      profileImageUrl: '/profile-images/v1/8.png',
    });
    await user.click(screen.getByRole('button', { name: '박쥐 프로필 사진' }));
    await user.click(screen.getByRole('button', { name: '저장' }));
    await waitFor(() => expect(dialog).not.toHaveAttribute('open'));
    await act(async (): Promise<void> => {
      finishOldRead?.(profile);
    });
    expect(screen.getByText('새프로필')).toBeInTheDocument();
    expect(screen.queryByText('연준')).not.toBeInTheDocument();
  });

  it('창이 열려 있을 때 저장 실패는 빨간 안내만 표시하고 입력을 유지한다', async (): Promise<void> => {
    mockUpdateProfile.mockRejectedValueOnce(
      problem(503, 'PROFILE_IMAGE_STORAGE_ERROR'),
    );
    const { user, dialog } = await openProfileModal();
    await user.click(screen.getByRole('button', { name: '박쥐 프로필 사진' }));
    await user.click(screen.getByRole('button', { name: '저장' }));
    expect(
      await screen.findByText(
        '프로필을 저장하지 못했어요. 다시 시도해 주세요.',
      ),
    ).toBeInTheDocument();
    expect(dialog).toHaveAttribute('open');
    expect(
      screen.getByRole('button', { name: '박쥐 프로필 사진' }),
    ).toHaveAttribute('aria-pressed', 'true');
    expect(mockAddToast).not.toHaveBeenCalled();
  });
});
