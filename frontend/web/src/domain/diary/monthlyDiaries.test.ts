import { afterEach, describe, expect, it, jest } from '@jest/globals';
import { RequestError, type ProblemDetails } from '../../shared/api';
import { getMonthlyDiaries } from './monthlyDiaries';

const mockAuthFetch = jest.fn<(...args: unknown[]) => Promise<Response>>();

jest.mock('../../shared/auth', () => ({
  authFetch: (...args: unknown[]) => mockAuthFetch(...args),
}));

const monthlyDiariesRequest = {
  year: 2026,
  month: 8 as const,
};

const monthlyDiariesResponse = {
  year: 2026,
  month: 8,
  days: [
    {
      date: '2026-08-12',
      exist: true,
      items: [
        {
          id: '06ed972e-0b79-4da0-9716-c9bd8faec85d',
          title: '비가 와도, 나는 괜찮았다.',
          thumbnailUrl: 'https://example.com/diary-thumbnail.png',
        },
      ],
    },
  ],
};

const problemDetails: ProblemDetails = {
  type: 'https://api.harudle.example/problems/monthly-diaries-failed',
  title: 'Monthly diaries unavailable',
  status: 503,
  detail: '월별 일기를 불러올 수 없습니다.',
  instance: '/api/v1/diaries?year=2026&month=8',
  code: 'MONTHLY_DIARIES_UNAVAILABLE',
  traceId: 'trace-id',
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

describe('월별 일기 조회 API', () => {
  it('조회에 성공하면 월별 일기를 반환한다', async () => {
    mockAuthFetch.mockResolvedValueOnce(
      createJsonResponse(monthlyDiariesResponse, 200),
    );

    await expect(getMonthlyDiaries(monthlyDiariesRequest)).resolves.toEqual(
      monthlyDiariesResponse,
    );
  });

  it('조회에 실패하면 RequestError를 던진다', async () => {
    mockAuthFetch.mockResolvedValueOnce(
      createJsonResponse(problemDetails, problemDetails.status),
    );

    await expect(
      getMonthlyDiaries(monthlyDiariesRequest),
    ).rejects.toBeInstanceOf(RequestError);
  });
});
