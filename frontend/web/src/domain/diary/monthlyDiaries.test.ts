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

    expect(mockAuthFetch).toHaveBeenCalledTimes(1);
    expect(mockAuthFetch).toHaveBeenCalledWith(
      '/api/v1/diaries?year=2026&month=8',
      { signal: undefined },
    );
  });

  it('성공 응답 형식이 잘못되면 검증 오류를 던진다', async () => {
    mockAuthFetch.mockResolvedValueOnce(
      createJsonResponse(
        {
          ...monthlyDiariesResponse,
          days: [
            {
              ...monthlyDiariesResponse.days[0],
              items: [
                {
                  id: 123,
                  title: '일기',
                  thumbnailUrl: 'https://example.com/image.png',
                },
              ],
            },
          ],
        },
        200,
      ),
    );

    await expect(getMonthlyDiaries(monthlyDiariesRequest)).rejects.toThrow(
      'MonthlyDiaries 응답 형식이 일치하지 않습니다.',
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

  it('실패 응답이 Problem Details 형식이 아니면 기본 오류를 던진다', async () => {
    mockAuthFetch.mockResolvedValueOnce(
      createJsonResponse({ message: 'Internal Server Error' }, 500),
    );

    await expect(getMonthlyDiaries(monthlyDiariesRequest)).rejects.toThrow(
      '월별 일기를 불러오는 중 문제가 발생했습니다. 다시 시도해주세요.',
    );
  });

  it('실패 응답을 JSON으로 파싱할 수 없으면 기본 오류를 던진다', async () => {
    mockAuthFetch.mockResolvedValueOnce({
      ...createJsonResponse(null, 500),
      json: async () => {
        throw new SyntaxError('Invalid JSON');
      },
    });

    await expect(getMonthlyDiaries(monthlyDiariesRequest)).rejects.toThrow(
      '월별 일기를 불러오는 중 문제가 발생했습니다. 다시 시도해주세요.',
    );
  });
});
