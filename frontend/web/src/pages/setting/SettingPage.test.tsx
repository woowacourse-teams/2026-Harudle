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
  Object.defineProperty(URL, 'createObjectURL', {
    configurable: true,
    value: jest.fn((): string => 'blob:profile-preview'),
  });
  Object.defineProperty(URL, 'revokeObjectURL', {
    configurable: true,
    value: jest.fn(),
  });
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

  it('개인 사진을 미리 보여주고 선택한 파일만 저장 요청에 담는다', async (): Promise<void> => {
    const { user, dialog } = await openProfileModal();
    const file = new File(['png'], 'photo.png', { type: 'image/png' });
    await user.upload(screen.getByLabelText('프로필 사진 파일'), file);
    await waitFor(() =>
      expect(screen.getByAltText('선택한 프로필 사진')).toHaveAttribute(
        'src',
        'blob:profile-preview',
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

  it('중복 저장을 막고 저장 실패는 토스트 대신 창 안에 표시한다', async (): Promise<void> => {
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
    expect(screen.getByRole('button', { name: '취소' })).toBeDisabled();
    await act(async (): Promise<void> => {
      failSave?.(problem(503, 'PROFILE_IMAGE_STORAGE_ERROR'));
    });
    expect(dialog).toHaveAttribute('open');
    expect(
      screen.getByText('프로필을 저장하지 못했어요. 다시 시도해 주세요.'),
    ).toBeInTheDocument();
    expect(mockAddToast).not.toHaveBeenCalled();
  });
});
