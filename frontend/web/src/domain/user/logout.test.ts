/** @jest-environment node */

import { ERROR_MESSAGES } from '../../shared/errorMessage';
import {
  afterEach,
  beforeEach,
  describe,
  expect,
  it,
  jest,
} from '@jest/globals';
import { RequestError, type ProblemDetails } from '../../shared/api';
import { logout } from './logout';

const mockFetch = jest.fn<typeof fetch>();
beforeEach((): void => {
  mockFetch.mockReset();
  jest.spyOn(globalThis, 'fetch').mockImplementation(mockFetch);
});

const createJsonResponse = (data: unknown, status: number): Response =>
  ({
    ok: status >= 200 && status < 300,
    status,
    json: async () => data,
  }) as Response;

afterEach(() => {
  jest.restoreAllMocks();
});

describe('로그아웃 API', () => {
  it('Access Token 복원 없이 쿠키와 CSRF Token만 전달해 로그아웃한다', async () => {
    mockFetch.mockResolvedValueOnce(createJsonResponse(null, 204));

    await expect(logout({ csrfToken: 'csrf-token' })).resolves.toBeUndefined();

    expect(mockFetch).toHaveBeenCalledTimes(1);
    expect(mockFetch).toHaveBeenCalledWith('/api/v1/auth/logout', {
      method: 'POST',
      credentials: 'include',
      headers: {
        'X-XSRF-TOKEN': 'csrf-token',
      },
    });
  });

  it('실패 응답을 JSON으로 파싱할 수 없으면 기본 오류를 던진다', async () => {
    mockFetch.mockResolvedValueOnce({
      ...createJsonResponse(null, 500),
      json: async () => {
        throw new SyntaxError('Invalid JSON');
      },
    });

    await expect(logout({ csrfToken: 'csrf-token' })).rejects.toThrow(
      ERROR_MESSAGES.LOGOUT_FAILED,
    );
  });

  it('실패 응답이 Problem Details 형식이 아니면 기본 오류를 던진다', async () => {
    mockFetch.mockResolvedValueOnce(
      createJsonResponse({ message: 'Internal Server Error' }, 500),
    );

    await expect(logout({ csrfToken: 'csrf-token' })).rejects.toThrow(
      ERROR_MESSAGES.LOGOUT_FAILED,
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
    mockFetch.mockResolvedValueOnce(
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
