import { ERROR_MESSAGES } from '../../shared/errorMessage';
import { afterEach, describe, expect, it, jest } from '@jest/globals';
import { RequestError, type ProblemDetails } from '../../shared/api';
import { GENERATION_FAILURE_CODES } from './diaryGeneration';
import {
  generateDiary,
  getDiaryGenerationStatus,
  isDiaryGenerateResponse,
  isDiaryGenerationStatusResponse,
  type DiaryGenerateRequest,
  type DiaryGenerateResponse,
} from './diaryGenerate';

const mockAuthFetch = jest.fn<(...args: unknown[]) => Promise<Response>>();

jest.mock('../../shared/auth', () => ({
  authFetch: (...args: unknown[]) => mockAuthFetch(...args),
}));

const diaryGenerateRequest: DiaryGenerateRequest = {
  diaryDate: '2026-09-09',
  sourceText: '오늘은 친구와 공원을 산책하며 즐거운 이야기를 나누었다.',
  idempotencyKey: '7e5cc251-fdde-4cc0-a54e-2c8142750609',
};

const createErrorResponse = (json: () => Promise<unknown>): Response =>
  ({
    ok: false,
    status: 500,
    json,
  }) as Response;

const createSuccessResponse = (
  status: DiaryGenerateResponse['generation']['status'],
  httpStatus: 200 | 202 = status === 'PENDING' ? 202 : 200,
): Response => {
  const isSucceeded = status === 'SUCCEEDED';

  return {
    ok: true,
    status: httpStatus,
    json: async () => ({
      id: '4f688273-37a3-453a-8348-b6596f4b4a61',
      diaryDate: diaryGenerateRequest.diaryDate,
      sourceText: diaryGenerateRequest.sourceText,
      createdAt: '2026-09-09T20:10:23+09:00',
      generation: {
        id: 'fc6f08df-61af-4420-b600-e5496c92e36f',
        status,
        title: isSucceeded ? '친구와 함께한 산책' : null,
        imageUrl: isSucceeded ? 'https://example.com/diary.png' : null,
        imageUrlExpiresAt: isSucceeded ? '2026-09-09T20:20:23+09:00' : null,
        completedAt: isSucceeded ? '2026-09-09T20:11:42+09:00' : null,
      },
      usage: {
        usageDate: '2026-09-09',
        usedCount: 1,
        limitCount: 3,
        remainingCount: 2,
      },
    }),
  } as Response;
};

afterEach(() => {
  mockAuthFetch.mockReset();
});

describe('일기 생성 API', () => {
  it('생성 요청의 URL, 메서드, 헤더와 본문을 전달한다', async () => {
    mockAuthFetch.mockResolvedValueOnce(createSuccessResponse('PENDING'));

    await generateDiary(diaryGenerateRequest);

    expect(mockAuthFetch).toHaveBeenCalledTimes(1);
    expect(mockAuthFetch).toHaveBeenCalledWith('/api/v1/diaries', {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'Idempotency-Key': diaryGenerateRequest.idempotencyKey,
      },
      body: JSON.stringify({
        diaryDate: diaryGenerateRequest.diaryDate,
        sourceText: diaryGenerateRequest.sourceText,
      }),
    });
  });

  it.each([
    { httpStatus: 202, status: 'PENDING' },
    { httpStatus: 200, status: 'PENDING' },
    { httpStatus: 200, status: 'PROCESSING' },
    { httpStatus: 200, status: 'SUCCEEDED' },
  ] as const)(
    '$httpStatus 응답에서 $status 상태를 반환한다',
    async ({ httpStatus, status }): Promise<void> => {
      mockAuthFetch.mockResolvedValueOnce(
        createSuccessResponse(status, httpStatus),
      );

      await expect(generateDiary(diaryGenerateRequest)).resolves.toMatchObject({
        generation: { status },
      });
    },
  );

  it('성공 응답 형식이 잘못되면 검증 오류를 던진다', async () => {
    const response = createSuccessResponse('SUCCEEDED');
    const data: unknown = await response.json();
    if (typeof data !== 'object' || data === null) {
      throw new Error('테스트 응답은 객체여야 합니다.');
    }
    mockAuthFetch.mockResolvedValueOnce({
      ...response,
      json: async () => ({ ...data, usage: null }),
    });

    await expect(generateDiary(diaryGenerateRequest)).rejects.toThrow(
      ERROR_MESSAGES.INVALID_DIARY_GENERATION_RESPONSE,
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

  it('실패 응답이 Problem Details 형식이 아니면 기본 오류를 던진다', async () => {
    mockAuthFetch.mockResolvedValueOnce(
      createErrorResponse(async () => ({ message: 'Internal Server Error' })),
    );

    await expect(generateDiary(diaryGenerateRequest)).rejects.toThrow(
      ERROR_MESSAGES.DIARY_GENERATION_FAILED,
    );
  });

  it('실패 응답을 JSON으로 파싱할 수 없으면 기본 오류를 던진다', async () => {
    mockAuthFetch.mockResolvedValueOnce(
      createErrorResponse(() =>
        Promise.reject(new SyntaxError('Invalid JSON')),
      ),
    );

    await expect(generateDiary(diaryGenerateRequest)).rejects.toThrow(
      ERROR_MESSAGES.DIARY_GENERATION_FAILED,
    );
  });
});

describe('일기 생성 접수 응답 타입 가드', (): void => {
  const pendingGeneration = {
    id: 'fc6f08df-61af-4420-b600-e5496c92e36f',
    status: 'PENDING',
    title: null,
    imageUrl: null,
    imageUrlExpiresAt: null,
    completedAt: null,
  };
  const succeededGeneration = {
    ...pendingGeneration,
    status: 'SUCCEEDED',
    title: '친구와 함께한 산책',
    imageUrl: 'https://example.com/diary.png',
    imageUrlExpiresAt: '2026-09-09T20:20:23+09:00',
    completedAt: '2026-09-09T20:11:42+09:00',
  };

  it.each([
    { ...pendingGeneration, status: 'FAILED' },
    { ...pendingGeneration, status: 'UNKNOWN' },
    { ...pendingGeneration, title: '아직 완성되지 않은 제목' },
    { ...pendingGeneration, status: 'PROCESSING', imageUrl: 'image.png' },
    { ...pendingGeneration, imageUrlExpiresAt: '2026-09-09T20:20:23+09:00' },
    { ...pendingGeneration, completedAt: '2026-09-09T20:11:42+09:00' },
    { ...succeededGeneration, title: null },
    { ...succeededGeneration, imageUrl: null },
    { ...succeededGeneration, imageUrlExpiresAt: null },
    { ...succeededGeneration, completedAt: null },
    { ...pendingGeneration, id: undefined },
    { ...pendingGeneration, title: undefined },
  ])(
    '상태와 결과 필드가 맞지 않는 응답을 거부한다: %j',
    async (generation): Promise<void> => {
      const data: unknown = await createSuccessResponse('PENDING').json();
      if (typeof data !== 'object' || data === null) {
        throw new Error('테스트 응답은 객체여야 합니다.');
      }
      expect(isDiaryGenerateResponse({ ...data, generation })).toBe(false);
    },
  );
});

describe('일기 생성 상태 응답 타입 가드', (): void => {
  const pendingResponse = {
    id: 'fc6f08df-61af-4420-b600-e5496c92e36f',
    status: 'PENDING',
    diaryId: null,
    errorCode: null,
  };

  it('새 오류 코드의 실패 응답을 검증하고 action을 그대로 반환한다', async (): Promise<void> => {
    const response = {
      ...pendingResponse,
      status: 'FAILED',
      errorCode: 'AI_CONTENT_BLOCKED',
      action:
        '작성한 내용으로 생성할 수 없어요. 내용을 바꿔 다시 시도해 주세요.',
    };
    mockAuthFetch.mockResolvedValueOnce({
      ok: true,
      json: async () => response,
    } as Response);

    await expect(
      getDiaryGenerationStatus({ generationId: pendingResponse.id }),
    ).resolves.toEqual(response);
  });

  it.each([
    pendingResponse,
    { ...pendingResponse, status: 'PROCESSING' },
    { ...pendingResponse, status: 'SUCCEEDED', diaryId: 'diary-id' },
    { ...pendingResponse, status: 'FAILED', errorCode: 'AI_PROVIDER_ERROR' },
  ])('정상 상태 응답을 허용한다: $status', (response): void => {
    expect(isDiaryGenerationStatusResponse(response)).toBe(true);
  });

  it.each([
    null,
    {},
    { ...pendingResponse, id: 1 },
    { ...pendingResponse, status: 'UNKNOWN' },
    { ...pendingResponse, diaryId: 1 },
    { ...pendingResponse, diaryId: undefined },
    { ...pendingResponse, errorCode: 1 },
    { ...pendingResponse, errorCode: undefined },
    { ...pendingResponse, diaryId: 'diary-id' },
    { ...pendingResponse, errorCode: 'AI_PROVIDER_ERROR' },
    { ...pendingResponse, status: 'PROCESSING', diaryId: 'diary-id' },
    {
      ...pendingResponse,
      status: 'PROCESSING',
      errorCode: 'AI_PROVIDER_ERROR',
    },
    { ...pendingResponse, status: 'SUCCEEDED' },
    {
      ...pendingResponse,
      status: 'SUCCEEDED',
      diaryId: 'diary-id',
      errorCode: 'AI_PROVIDER_ERROR',
    },
    { ...pendingResponse, status: 'FAILED' },
    {
      ...pendingResponse,
      status: 'FAILED',
      diaryId: 'diary-id',
      errorCode: 'AI_PROVIDER_ERROR',
    },
    { ...pendingResponse, status: 'FAILED', errorCode: '' },
    { ...pendingResponse, status: 'FAILED', errorCode: 'UNKNOWN_ERROR' },
  ])('필수 필드나 타입이 잘못된 응답을 거부한다: %j', (response): void => {
    expect(isDiaryGenerationStatusResponse(response)).toBe(false);
  });

  it.each(GENERATION_FAILURE_CODES)(
    '명세의 생성 실패 코드를 허용한다: %s',
    (errorCode): void => {
      expect(
        isDiaryGenerationStatusResponse({
          ...pendingResponse,
          status: 'FAILED',
          errorCode,
        }),
      ).toBe(true);
    },
  );
});
