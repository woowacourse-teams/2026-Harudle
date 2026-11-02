import { ERROR_MESSAGES } from '../../shared/errorMessage';
import { afterEach, describe, expect, it, jest } from '@jest/globals';
import { RequestError, type ProblemDetails } from '../../shared/api';
import {
  getUserProfile,
  updateUserNickname,
  updateUserProfile,
  ProfileSaveResultUnknownError,
} from './profile';

const mockAuthFetch = jest.fn<(...args: unknown[]) => Promise<Response>>();

jest.mock('../../shared/auth', () => ({
  authFetch: (...args: unknown[]) => mockAuthFetch(...args),
}));

const profile = {
  id: '0550ds07-01ce-4d68-81a2-a2fa70982a25',
  name: '정연준',
  nickname: '연준',
  profileImageUrl: '/profile.svg',
  email: null,
  role: 'ADMIN',
  oauthProviders: ['kakao'],
  createdAt: '2026-08-13T08:06:11.371007Z',
};

const createJsonResponse = (data: unknown, status: number): Response =>
  ({
    ok: status >= 200 && status < 300,
    status,
    json: async () => data,
  }) as Response;

afterEach(() => {
  mockAuthFetch.mockReset();
});

describe('프로필 조회 API', () => {
  it('프로필 조회에 성공하면 프로필 정보를 반환한다', async () => {
    mockAuthFetch.mockResolvedValueOnce(createJsonResponse(profile, 200));

    await expect(getUserProfile()).resolves.toEqual(profile);

    expect(mockAuthFetch).toHaveBeenCalledTimes(1);
    expect(mockAuthFetch).toHaveBeenCalledWith('/api/v1/me');
  });

  it.each([
    ['지원하지 않는 사용자 역할', { ...profile, role: 'GUEST' }],
    [
      '지원하지 않는 OAuth Provider',
      { ...profile, oauthProviders: ['google'] },
    ],
    ['비어 있는 OAuth Provider 목록', { ...profile, oauthProviders: [] }],
    ['잘못된 닉네임 타입', { ...profile, nickname: null }],
    ['잘못된 프로필 이미지 타입', { ...profile, profileImageUrl: 123 }],
    ['잘못된 이메일 타입', { ...profile, email: 123 }],
  ])('%s이면 응답 형식 오류를 던진다', async (_, invalidProfile) => {
    mockAuthFetch.mockResolvedValueOnce(
      createJsonResponse(invalidProfile, 200),
    );

    await expect(getUserProfile()).rejects.toThrow(
      ERROR_MESSAGES.INVALID_PROFILE_RESPONSE,
    );
  });

  it('성공 응답을 JSON으로 파싱할 수 없으면 파싱 오류를 던진다', async () => {
    mockAuthFetch.mockResolvedValueOnce({
      ...createJsonResponse(null, 200),
      json: async () => {
        throw new SyntaxError('Invalid JSON');
      },
    });

    await expect(getUserProfile()).rejects.toThrow(SyntaxError);
  });

  it('실패 응답을 JSON으로 파싱할 수 없으면 기본 오류를 던진다', async () => {
    mockAuthFetch.mockResolvedValueOnce({
      ...createJsonResponse(null, 500),
      json: async () => {
        throw new SyntaxError('Invalid JSON');
      },
    });

    await expect(getUserProfile()).rejects.toThrow(
      ERROR_MESSAGES.PROFILE_FETCH_FAILED,
    );
  });

  it('실패 응답이 Problem Details 형식이 아니면 기본 오류를 던진다', async () => {
    mockAuthFetch.mockResolvedValueOnce(
      createJsonResponse({ message: 'Internal Server Error' }, 500),
    );

    await expect(getUserProfile()).rejects.toThrow(
      ERROR_MESSAGES.PROFILE_FETCH_FAILED,
    );
  });

  it('실패 응답이 Problem Details 형식이면 RequestError를 던진다', async () => {
    const problemDetails: ProblemDetails = {
      type: 'about:blank',
      title: 'Service Unavailable',
      status: 503,
      detail: '프로필을 조회하지 못했습니다.',
      instance: '/api/v1/me',
      code: 'PROFILE_REQUEST_FAILED',
      traceId: 'trace-id',
    };
    mockAuthFetch.mockResolvedValueOnce(
      createJsonResponse(problemDetails, problemDetails.status),
    );

    const result = getUserProfile();

    await expect(result).rejects.toBeInstanceOf(RequestError);
    await expect(result).rejects.toMatchObject({
      message: problemDetails.detail,
      problem: problemDetails,
    });
  });
});

describe('닉네임 수정 API', () => {
  it('PATCH로 닉네임을 보내고 204 응답은 본문을 파싱하지 않는다', async () => {
    const json = jest
      .fn<() => Promise<unknown>>()
      .mockRejectedValue(new SyntaxError('Unexpected end of JSON input'));
    mockAuthFetch.mockResolvedValueOnce({
      ...createJsonResponse(null, 204),
      json,
    });
    await expect(updateUserNickname('새닉네임')).resolves.toBeUndefined();
    expect(json).not.toHaveBeenCalled();
    expect(mockAuthFetch).toHaveBeenCalledWith('/api/v1/me/nickname', {
      method: 'PATCH',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ nickname: '새닉네임' }),
    });
  });

  it('중복 오류 코드를 보존한다', async () => {
    const problem = {
      type: 'about:blank',
      title: 'Conflict',
      status: 409,
      detail: '중복',
      instance: '/api/v1/me/nickname',
      code: 'NICKNAME_ALREADY_IN_USE',
    };
    mockAuthFetch.mockResolvedValueOnce(createJsonResponse(problem, 409));
    await expect(updateUserNickname('네오')).rejects.toMatchObject({ problem });
  });
});

describe('통합 프로필 수정 API', (): void => {
  it('multipart에 바뀐 닉네임과 기본 번호를 담고 204 본문을 읽지 않는다', async (): Promise<void> => {
    const json = jest.fn<() => Promise<unknown>>();
    mockAuthFetch.mockResolvedValueOnce({
      ...createJsonResponse(null, 204),
      json,
    });
    await updateUserProfile({
      nickname: '새닉네임',
      image: { type: 'default', code: 8 },
    });
    const call = mockAuthFetch.mock.calls[0];
    expect(call[0]).toBe('/api/v1/me/profile');
    const init = call[1];
    if (
      typeof init !== 'object' ||
      init === null ||
      !('body' in init) ||
      !(init.body instanceof FormData)
    )
      throw new Error('FormData 요청이 아닙니다.');
    expect(init.body.get('nickname')).toBe('새닉네임');
    expect(init.body.get('imageCode')).toBe('8');
    expect(init.body.has('image')).toBe(false);
    expect(init).not.toHaveProperty('headers');
    expect(json).not.toHaveBeenCalled();
  });

  it('개인 사진은 image에 담고 기본 번호와 변경하지 않은 닉네임을 생략한다', async (): Promise<void> => {
    mockAuthFetch.mockResolvedValueOnce(createJsonResponse(null, 204));
    const file = new File(['png'], 'photo.png', { type: 'image/png' });
    await updateUserProfile({ image: { type: 'upload', file } });
    const init = mockAuthFetch.mock.calls[0][1];
    if (
      typeof init !== 'object' ||
      init === null ||
      !('body' in init) ||
      !(init.body instanceof FormData)
    )
      throw new Error('FormData 요청이 아닙니다.');
    expect(init.body.get('image')).toEqual(file);
    expect(init.body.has('imageCode')).toBe(false);
    expect(init.body.has('nickname')).toBe(false);
  });

  it.each([413, 503])(
    '확인된 %i 오류의 상태와 코드를 보존한다',
    async (status): Promise<void> => {
      const problem = {
        type: 'about:blank',
        title: 'Error',
        status,
        detail: '사진 오류',
        instance: '/api/v1/me/profile',
        code: status === 503 ? 'PROFILE_IMAGE_STORAGE_ERROR' : `HTTP_${status}`,
      };
      mockAuthFetch.mockResolvedValueOnce(createJsonResponse(problem, status));
      await expect(
        updateUserProfile({ nickname: '새닉네임' }),
      ).rejects.toMatchObject({ problem });
    },
  );

  it.each([500])(
    '%i 응답만으로 저장 실패나 성공을 단정하지 않는다',
    async (status): Promise<void> => {
      mockAuthFetch.mockResolvedValueOnce(createJsonResponse(null, status));
      await expect(
        updateUserProfile({ nickname: '새닉네임' }),
      ).rejects.toBeInstanceOf(ProfileSaveResultUnknownError);
    },
  );

  it('네트워크 오류는 저장 결과 불명확 상태로 구분하고 자동 재전송하지 않는다', async (): Promise<void> => {
    mockAuthFetch.mockRejectedValueOnce(new TypeError('Failed to fetch'));
    await expect(
      updateUserProfile({ nickname: '새닉네임' }),
    ).rejects.toBeInstanceOf(ProfileSaveResultUnknownError);
    expect(mockAuthFetch).toHaveBeenCalledTimes(1);
  });
});
