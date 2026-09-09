import { afterEach, describe, expect, it, jest } from '@jest/globals';
import { RequestError, type ProblemDetails } from '../../shared/api';
import { generateDiary, type DiaryGenerateRequest } from './diaryGenerate';

const mockAuthFetch = jest.fn<(...args: unknown[]) => Promise<Response>>();

jest.mock('../../shared/auth', () => ({
  authFetch: (...args: unknown[]) => mockAuthFetch(...args),
}));

const diaryGenerateRequest: DiaryGenerateRequest = {
  diaryDate: '2026-09-09',
  sourceText: '오늘은 친구와 공원을 산책하며 즐거운 이야기를 나누었다.',
  idempotencyKey: '7e5cc251-fdde-4cc0-a54e-2c8142750609',
};

const fallbackErrorMessage =
  '일기 생성 도중 문제가 발생했습니다. 다시 시도해주세요.';

const createErrorResponse = (json: () => Promise<unknown>): Response =>
  ({
    ok: false,
    status: 500,
    json,
  }) as Response;

afterEach(() => {
  mockAuthFetch.mockReset();
});

describe('일기 생성 API', () => {
  it('실패 응답을 JSON으로 파싱할 수 없으면 기본 오류를 던진다', async () => {
    mockAuthFetch.mockResolvedValueOnce(
      createErrorResponse(() =>
        Promise.reject(new SyntaxError('Invalid JSON')),
      ),
    );

    await expect(generateDiary(diaryGenerateRequest)).rejects.toThrow(
      fallbackErrorMessage,
    );
  });

  it('실패 응답이 Problem Details 형식이 아니면 기본 오류를 던진다', async () => {
    mockAuthFetch.mockResolvedValueOnce(
      createErrorResponse(async () => ({ message: 'Internal Server Error' })),
    );

    await expect(generateDiary(diaryGenerateRequest)).rejects.toThrow(
      fallbackErrorMessage,
    );
  });

  it('실패 응답이 Problem Details 형식이면 RequestError를 던진다', async () => {
    const problemDetails: ProblemDetails = {
      type: 'about:blank',
      title: 'Service Unavailable',
      status: 503,
      detail: '일기를 만드는 중 문제가 발생했습니다. 다시 시도해주세요.',
      instance: '/api/v1/diaries',
      code: 'DIARY_GENERATION_FAILED',
      traceId: 'trace-id',
    };
    mockAuthFetch.mockResolvedValueOnce(
      createErrorResponse(async () => problemDetails),
    );

    const result = generateDiary(diaryGenerateRequest);

    await expect(result).rejects.toBeInstanceOf(RequestError);
    await expect(result).rejects.toMatchObject({
      message: problemDetails.detail,
      problem: problemDetails,
    });
  });
});
