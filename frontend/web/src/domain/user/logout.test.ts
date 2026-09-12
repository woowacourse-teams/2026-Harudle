import { afterEach, describe, expect, it, jest } from '@jest/globals';
import { RequestError, type ProblemDetails } from '../../shared/api';
import { logout } from './logout';

const mockAuthFetch = jest.fn<(...args: unknown[]) => Promise<Response>>();

jest.mock('../../shared/auth', () => ({
  authFetch: (...args: unknown[]) => mockAuthFetch(...args),
}));

const fallbackErrorMessage =
  '로그아웃하는 중 문제가 발생했습니다. 다시 시도해주세요.';

const createJsonResponse = (data: unknown, status: number): Response =>
  ({
    ok: status >= 200 && status < 300,
    status,
    json: async () => data,
  }) as Response;

afterEach(() => {
  mockAuthFetch.mockReset();
});

describe('로그아웃 API', () => {
  it('CSRF Token을 헤더에 담아 로그아웃을 요청한다', async () => {
    mockAuthFetch.mockResolvedValueOnce(createJsonResponse(null, 204));

    await expect(logout({ csrfToken: 'csrf-token' })).resolves.toBeUndefined();

    expect(mockAuthFetch).toHaveBeenCalledWith('/api/v1/auth/logout', {
      method: 'POST',
      headers: {
        'X-XSRF-TOKEN': 'csrf-token',
      },
    });
  });

  it('실패 응답을 JSON으로 파싱할 수 없으면 기본 오류를 던진다', async () => {
    mockAuthFetch.mockResolvedValueOnce({
      ...createJsonResponse(null, 500),
      json: async () => {
        throw new SyntaxError('Invalid JSON');
      },
    });

    await expect(logout({ csrfToken: 'csrf-token' })).rejects.toThrow(
      fallbackErrorMessage,
    );
  });

  it('실패 응답이 Problem Details 형식이 아니면 기본 오류를 던진다', async () => {
    mockAuthFetch.mockResolvedValueOnce(
      createJsonResponse({ message: 'Internal Server Error' }, 500),
    );

    await expect(logout({ csrfToken: 'csrf-token' })).rejects.toThrow(
      fallbackErrorMessage,
    );
  });

  it('실패 응답이 Problem Details 형식이면 RequestError를 던진다', async () => {
    const problemDetails: ProblemDetails = {
      type: 'about:blank',
      title: 'Unauthorized',
      status: 401,
      detail: '로그아웃할 수 없습니다.',
      instance: '/api/v1/auth/logout',
      code: 'LOGOUT_FAILED',
      traceId: 'trace-id',
    };
    mockAuthFetch.mockResolvedValueOnce(
      createJsonResponse(problemDetails, problemDetails.status),
    );

    const result = logout({ csrfToken: 'csrf-token' });

    await expect(result).rejects.toBeInstanceOf(RequestError);
    await expect(result).rejects.toMatchObject({
      message: problemDetails.detail,
      problem: problemDetails,
    });
  });
});
