import { ERROR_MESSAGES } from '../../shared/errorMessage';
import { afterEach, describe, expect, it, jest } from '@jest/globals';
import { RequestError, type ProblemDetails } from '../../shared/api';
import { getUserProfile } from './profile';

const mockAuthFetch = jest.fn<(...args: unknown[]) => Promise<Response>>();

jest.mock('../../shared/auth', () => ({
  authFetch: (...args: unknown[]) => mockAuthFetch(...args),
}));

const profile = {
  id: '0550ds07-01ce-4d68-81a2-a2fa70982a25',
  name: '정연준',
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
