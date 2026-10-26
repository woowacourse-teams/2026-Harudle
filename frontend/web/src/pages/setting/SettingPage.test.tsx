import { beforeEach, describe, expect, it, jest } from '@jest/globals';
import {
  act,
  fireEvent,
  render,
  renderHook,
  screen,
  waitFor,
} from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import SettingPage from './SettingPage';
import useProfileUpdate from './useProfileUpdate';
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
    close: {
      value: (): void => {
        dialog.removeAttribute('open');
        fireEvent(dialog, new Event('close'));
      },
    },
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

describe('프로필 리뷰 수정 회귀', (): void => {
  it.each([
    new File(['gif'], 'invalid.gif', { type: 'image/gif' }),
    new File([], 'empty.png', { type: 'image/png' }),
    new File([new Uint8Array(5_000_001)], 'large.png', { type: 'image/png' }),
  ])(
    '잘못된 새 파일을 거절해도 기존 사진으로 닉네임을 저장한다: $name',
    async (file): Promise<void> => {
      const { user, input, dialog } = await openProfileModal();
      await user.clear(input);
      await user.type(input, '새닉네임');
      await user.upload(screen.getByLabelText('프로필 사진 파일'), file);
      expect(screen.getByRole('alert')).toBeInTheDocument();
      expect(screen.getByRole('button', { name: '저장' })).toBeEnabled();
      await user.click(screen.getByRole('button', { name: '저장' }));
      await waitFor(() => expect(dialog).not.toHaveAttribute('open'));
      expect(mockUpdateProfile).toHaveBeenCalledWith({ nickname: '새닉네임' });
    },
  );

  it('5MB 경계 파일은 허용하고 거절한 파일 대신 이전의 유효한 파일을 보낸다', async (): Promise<void> => {
    const { user, dialog } = await openProfileModal();
    const accepted = new File([new Uint8Array(5_000_000)], 'accepted.png', {
      type: 'image/png',
    });
    await user.upload(screen.getByLabelText('프로필 사진 파일'), accepted);
    await user.upload(
      screen.getByLabelText('프로필 사진 파일'),
      new File(['gif'], 'invalid.gif', { type: 'image/gif' }),
    );
    expect(screen.getByRole('button', { name: '저장' })).toBeEnabled();
    await user.click(screen.getByRole('button', { name: '저장' }));
    await waitFor(() => expect(dialog).not.toHaveAttribute('open'));
    expect(mockUpdateProfile).toHaveBeenCalledWith({
      image: { type: 'upload', file: accepted },
    });
  });

  it('업로드 미리보기 실패 시 기존 사진으로 복구하고 주소를 해제한다', async (): Promise<void> => {
    const { user, input } = await openProfileModal();
    await user.clear(input);
    await user.type(input, '새닉네임');
    await user.upload(
      screen.getByLabelText('프로필 사진 파일'),
      new File(['png'], 'photo.png', { type: 'image/png' }),
    );
    fireEvent.error(screen.getByAltText('선택한 프로필 사진'));
    expect(screen.getByRole('button', { name: '저장' })).toBeDisabled();
    await user.click(screen.getByRole('button', { name: '되돌리기' }));
    expect(screen.getByRole('button', { name: '저장' })).toBeEnabled();
    expect(URL.revokeObjectURL).toHaveBeenCalledWith('blob:profile-preview');
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
  });

  it('기본 사진 표시 실패는 선택을 막지 않고 다시 불러오거나 창을 다시 열어 복구한다', async (): Promise<void> => {
    const { user, dialog } = await openProfileModal();
    const candidate = screen.getByRole('button', { name: '박쥐 프로필 사진' });
    const image = candidate.querySelector('img');
    if (!image) throw new Error('사진이 없습니다.');
    fireEvent.error(image);
    expect(candidate).toBeEnabled();
    expect(image).toHaveAttribute('src', 'fallback.svg');
    await user.click(
      screen.getByRole('button', { name: '사진 다시 불러오기' }),
    );
    expect(candidate.querySelector('img')).toHaveAttribute(
      'src',
      '/profile-images/v1/8.png',
    );
    fireEvent.error(candidate.querySelector('img') ?? image);
    await user.click(screen.getByRole('button', { name: '취소' }));
    expect(dialog).not.toHaveAttribute('open');
    await user.click(screen.getByRole('button', { name: '프로필 수정' }));
    expect(
      screen.queryByRole('button', { name: '사진 다시 불러오기' }),
    ).not.toBeInTheDocument();
    expect(
      screen
        .getByRole('button', { name: '박쥐 프로필 사진' })
        .querySelector('img'),
    ).toHaveAttribute('src', '/profile-images/v1/8.png');
  });

  it('저장과 재조회 중 배경과 ESC 닫기를 막고 강제 닫힘도 복구한다', async (): Promise<void> => {
    let finishSave: (() => void) | undefined;
    let finishFetch: ((value: typeof profile) => void) | undefined;
    mockUpdateProfile.mockImplementationOnce(
      () =>
        new Promise((resolve): void => {
          finishSave = resolve;
        }),
    );
    const { user, dialog, input } = await openProfileModal();
    mockGetProfile.mockImplementationOnce(
      () =>
        new Promise((resolve): void => {
          finishFetch = resolve;
        }),
    );
    await user.click(screen.getByRole('button', { name: '박쥐 프로필 사진' }));
    await user.click(screen.getByRole('button', { name: '저장' }));
    for (const phase of ['saving', 'refreshing']) {
      fireEvent.click(dialog);
      expect(dialog).toHaveAttribute('open');
      expect(
        fireEvent.keyDown(input, { key: 'Escape', cancelable: true }),
      ).toBe(false);
      expect(fireEvent(dialog, new Event('cancel', { cancelable: true }))).toBe(
        false,
      );
      dialog.removeAttribute('open');
      fireEvent(dialog, new Event('close'));
      expect(dialog).toHaveAttribute('open');
      if (phase === 'saving')
        await act(async (): Promise<void> => {
          finishSave?.();
        });
    }
    await act(async (): Promise<void> => {
      finishFetch?.(profile);
    });
    expect(dialog).not.toHaveAttribute('open');
  });

  it('한글 조합 중 Enter로 저장하지 않고 조합이 끝난 뒤 저장한다', async (): Promise<void> => {
    const { user, input } = await openProfileModal();
    fireEvent.compositionStart(input);
    fireEvent.change(input, { target: { value: '새닉네임' } });
    expect(fireEvent.keyDown(input, { key: 'Enter', isComposing: true })).toBe(
      false,
    );
    const form = input.closest('form');
    if (!form) throw new Error('폼이 없습니다.');
    fireEvent.submit(form);
    expect(mockUpdateProfile).not.toHaveBeenCalled();
    fireEvent.compositionEnd(input, { target: { value: '새닉네임' } });
    await user.click(screen.getByRole('button', { name: '저장' }));
    expect(mockUpdateProfile).toHaveBeenCalledWith({ nickname: '새닉네임' });
  });

  it('사진 주소의 오래된 조회가 저장 후 최신 프로필을 덮어쓰지 않는다', async (): Promise<void> => {
    let finishOldFetch: ((value: typeof profile) => void) | undefined;
    const { user, dialog } = await openProfileModal();
    mockGetProfile.mockImplementationOnce(
      () =>
        new Promise((resolve): void => {
          finishOldFetch = resolve;
        }),
    );
    fireEvent.error(
      screen
        .getByRole('button', { name: '프로필 사진 수정' })
        .querySelector('img') ?? dialog,
    );
    mockGetProfile.mockResolvedValueOnce({
      ...profile,
      nickname: '최신닉네임',
    });
    await user.click(screen.getByRole('button', { name: '박쥐 프로필 사진' }));
    await user.click(screen.getByRole('button', { name: '저장' }));
    await screen.findByText('최신닉네임');
    await act(async (): Promise<void> => {
      finishOldFetch?.(profile);
    });
    expect(screen.getByText('최신닉네임')).toBeInTheDocument();
    expect(screen.queryByText('연준')).not.toBeInTheDocument();
  });
});

describe('프로필 요청 훅', (): void => {
  it('같은 순간에 호출해도 훅이 중복 저장과 재조회를 막는다', async (): Promise<void> => {
    let finishSave: (() => void) | undefined;
    mockUpdateProfile.mockImplementationOnce(
      () =>
        new Promise((resolve): void => {
          finishSave = resolve;
        }),
    );
    const { result } = renderHook(() => useProfileUpdate());
    let firstSave: ReturnType<typeof result.current.saveProfile> | undefined;
    await act(async (): Promise<void> => {
      firstSave = result.current.saveProfile({ nickname: '새닉네임' });
      await result.current.saveProfile({ nickname: '중복요청' });
      await result.current.retryProfileFetch();
    });
    expect(mockUpdateProfile).toHaveBeenCalledTimes(1);
    expect(mockGetProfile).not.toHaveBeenCalled();
    mockGetProfile.mockRejectedValueOnce(new Error('조회 실패'));
    await act(async (): Promise<void> => {
      finishSave?.();
      await firstSave;
    });
    let finishFetch: ((value: typeof profile) => void) | undefined;
    mockGetProfile.mockImplementationOnce(
      () =>
        new Promise((resolve): void => {
          finishFetch = resolve;
        }),
    );
    let firstFetch:
      ReturnType<typeof result.current.retryProfileFetch> | undefined;
    await act(async (): Promise<void> => {
      firstFetch = result.current.retryProfileFetch();
      await result.current.retryProfileFetch();
      await result.current.saveProfile({ nickname: '중복저장' });
    });
    expect(mockUpdateProfile).toHaveBeenCalledTimes(1);
    expect(mockGetProfile).toHaveBeenCalledTimes(2);
    await act(async (): Promise<void> => {
      finishFetch?.(profile);
      await firstFetch;
    });
    expect(result.current.request.status).toBe('idle');
    expect(result.current.isPending()).toBe(false);
  });

  it('서버 필드 이름으로 오류를 나누고 수정한 필드의 오류만 지운다', async (): Promise<void> => {
    const failure = new RequestError({
      type: 'about:blank',
      title: 'Error',
      status: 400,
      code: 'VALIDATION_ERROR',
      detail: '안내 문구는 달라져도 됩니다.',
      instance: '/api/v1/me/profile',
      errors: [
        { field: 'nickname', reason: '닉네임을 확인해 주세요.' },
        { field: 'image', reason: '사진을 확인해 주세요.' },
      ],
    });
    mockUpdateProfile.mockRejectedValueOnce(failure);
    const { result } = renderHook(() => useProfileUpdate());
    await act(async (): Promise<void> => {
      await result.current.saveProfile({ nickname: '새닉네임' });
    });
    expect(result.current.errors).toEqual({
      nickname: '닉네임을 확인해 주세요.',
      photo: '사진을 확인해 주세요.',
      form: '',
    });
    act((): void => result.current.clearFieldError('nickname'));
    expect(result.current.errors.photo).toBe('사진을 확인해 주세요.');
    expect(result.current.errors.nickname).toBe('');
    expect(result.current.request.status).toBe('idle');
    mockUpdateProfile.mockRejectedValueOnce(problem(400, 'VALIDATION_ERROR'));
    await act(async (): Promise<void> => {
      await result.current.saveProfile({ nickname: '새닉네임' });
    });
    expect(result.current.errors).toEqual({
      nickname: '',
      photo: '',
      form: '서버 오류',
    });
  });
});
