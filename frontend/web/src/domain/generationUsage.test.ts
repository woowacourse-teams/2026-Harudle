import { afterEach, describe, expect, it, jest } from '@jest/globals';
import { RequestError, type ProblemDetails } from '../shared/api';
import { getGenerationUsage } from './generationUsage';

const mockAuthFetch = jest.fn<(...args: unknown[]) => Promise<Response>>();

jest.mock('../shared/auth', () => ({
  authFetch: (...args: unknown[]) => mockAuthFetch(...args),
}));

const data = {
  usageDate: '2026-09-11',
  usedCount: 1,
  limitCount: 3,
  remainingCount: 2,
};

const fallbackErrorMessage =
  '남은 생성 횟수를 조회하는 중 에러가 발생했습니다. 다시 시도해주세요.';

const createJsonResponse = (data: unknown, status: number): Response =>
  ({
    ok: status >= 200 && status < 300,
    status,
    json: async () => data,
  }) as Response;

afterEach(() => {
  mockAuthFetch.mockReset();
});

describe('생성 횟수 조회 API', () => {
  it('조회에 성공하면 생성 횟수 정보를 반환한다', async () => {
    mockAuthFetch.mockResolvedValueOnce(createJsonResponse(data, 200));

    await expect(getGenerationUsage()).resolves.toEqual(data);

    expect(mockAuthFetch).toHaveBeenCalledTimes(1);
    expect(mockAuthFetch).toHaveBeenCalledWith('/api/v1/me/generation-usage');
  });

  it('성공 응답 형식이 잘못되면 검증 오류를 던진다', async () => {
    mockAuthFetch.mockResolvedValueOnce(
      createJsonResponse({ ...data, remainingCount: '2' }, 200),
    );

    await expect(getGenerationUsage()).rejects.toThrow(
      'GenerationUsage 응답 형식이 일치하지 않습니다.',
    );
  });

  it('실패 응답이 Problem Details 형식이면 RequestError를 던진다', async () => {
    const problemDetails: ProblemDetails = {
      type: 'about:blank',
      title: 'Service Unavailable',
      status: 503,
      detail: '생성 횟수 정보를 불러오지 못했습니다.',
      instance: '/api/v1/me/generation-usage',
      code: 'SERVICE_UNAVAILABLE',
    };
    mockAuthFetch.mockResolvedValueOnce(
      createJsonResponse(problemDetails, problemDetails.status),
    );

    const result = getGenerationUsage();

    await expect(result).rejects.toBeInstanceOf(RequestError);
    await expect(result).rejects.toMatchObject({
      message: problemDetails.detail,
      problem: problemDetails,
    });
  });

  it('실패 응답이 Problem Details 형식이 아니면 기본 오류를 던진다', async () => {
    mockAuthFetch.mockResolvedValueOnce(
      createJsonResponse({ message: 'Internal Server Error' }, 500),
    );

    await expect(getGenerationUsage()).rejects.toThrow(fallbackErrorMessage);
  });

  it('실패 응답을 JSON으로 파싱할 수 없으면 기본 오류를 던진다', async () => {
    mockAuthFetch.mockResolvedValueOnce({
      ...createJsonResponse(null, 500),
      json: async () => {
        throw new SyntaxError('Invalid JSON');
      },
    });

    await expect(getGenerationUsage()).rejects.toThrow(fallbackErrorMessage);
  });
});
