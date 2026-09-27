import { ERROR_MESSAGES } from '../shared/errorMessage';
import { afterEach, describe, expect, it, jest } from '@jest/globals';
import { RequestError, type ProblemDetails } from '../shared/api';
import { getCurrentStreak } from './currentStreak';

const mockAuthFetch = jest.fn<(...args: unknown[]) => Promise<Response>>();

jest.mock('../shared/auth', () => ({
  authFetch: (...args: unknown[]) => mockAuthFetch(...args),
}));

const data = {
  streakCount: 3,
  recordedToday: true,
  days: [
    {
      date: '2026-09-11',
      items: [
        {
          id: '00000000-0000-4000-8000-000000000001',
          title: '즐거운 하루',
          thumbnailUrl: 'https://example.com/diary.png',
        },
      ],
    },
  ],
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

describe('연속 기록 조회 API', () => {
  it('조회에 성공하면 연속 기록 정보를 반환한다', async () => {
    mockAuthFetch.mockResolvedValueOnce(createJsonResponse(data, 200));

    await expect(getCurrentStreak()).resolves.toEqual(data);

    expect(mockAuthFetch).toHaveBeenCalledTimes(1);
    expect(mockAuthFetch).toHaveBeenCalledWith(
      '/api/v1/diaries/current-streak',
    );
  });

  it('성공 응답 형식이 잘못되면 검증 오류를 던진다', async () => {
    mockAuthFetch.mockResolvedValueOnce(
      createJsonResponse(
        {
          ...data,
          days: [{ date: '2026-09-11', items: [{ id: 'diary-1' }] }],
        },
        200,
      ),
    );

    await expect(getCurrentStreak()).rejects.toThrow(
      ERROR_MESSAGES.INVALID_CURRENT_STREAK_RESPONSE,
    );
  });

  it('실패 응답이 Problem Details 형식이면 RequestError를 던진다', async () => {
    const problemDetails: ProblemDetails = {
      type: 'about:blank',
      title: 'Service Unavailable',
      status: 503,
      detail: '연속 기록 정보를 불러오지 못했습니다.',
      instance: '/api/v1/diaries/current-streak',
      code: 'SERVICE_UNAVAILABLE',
    };
    mockAuthFetch.mockResolvedValueOnce(
      createJsonResponse(problemDetails, problemDetails.status),
    );

    const result = getCurrentStreak();

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

    await expect(getCurrentStreak()).rejects.toThrow(
      ERROR_MESSAGES.CURRENT_STREAK_FETCH_FAILED,
    );
  });

  it('실패 응답을 JSON으로 파싱할 수 없으면 기본 오류를 던진다', async () => {
    mockAuthFetch.mockResolvedValueOnce({
      ...createJsonResponse(null, 500),
      json: async () => {
        throw new SyntaxError('Invalid JSON');
      },
    });

    await expect(getCurrentStreak()).rejects.toThrow(
      ERROR_MESSAGES.CURRENT_STREAK_FETCH_FAILED,
    );
  });
});
