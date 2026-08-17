import { delay, http, HttpResponse } from 'msw';

interface CreateDiaryRequest {
  diaryDate: string;
  sourceText: string;
}

interface CreateDiaryResponse {
  id: string;
  diaryDate: string;
  sourceText: string;
  createdAt: string;
  generation: {
    id: string;
    status: 'SUCCEEDED';
    title: string;
    imageUrl: string;
    imageUrlExpiresAt: string;
    completedAt: string;
  };
  usage: {
    usageDate: string;
    usedCount: number;
    limitCount: number;
    remainingCount: number;
  };
}

interface DiaryDetailResponse {
  id: string;
  diaryDate: string;
  sourceText: string;
  createdAt: string;
  generation: {
    id: string;
    status: 'SUCCEEDED';
    title: string;
    imageUrl: string;
    imageUrlExpiresAt: string;
    completedAt: string;
  };
}

interface DiaryShareLinkResponse {
  shareId: string;
  shareUrl: string;
  createdAt: string;
}

interface ValidationError {
  field: string;
  reason: string;
}

const MOCK_USAGE_DATE = '2026-08-12';
const DAILY_GENERATION_LIMIT = 3;

let usedGenerationCount = 0;
let mockDiarySequence = 1;
let mockShareSequence = 1;

const createdDiaryRequests = new Map<
  string,
  { fingerprint: string; response: CreateDiaryResponse }
>();
const createdDiaryDetails = new Map<string, DiaryDetailResponse>();
const mockDiaryShareLinks = new Map<string, DiaryShareLinkResponse>();

const diaryThumbnailUrl = new URL(
  '../assets/images/diary-four-panel.png',
  import.meta.url,
).href;

const SAMPLE_DIARY_ID = '00000000-0000-4000-8000-000000000001';

const isRecord = (value: unknown): value is Record<string, unknown> => {
  return typeof value === 'object' && value !== null;
};

const isCreateDiaryRequest = (value: unknown): value is CreateDiaryRequest => {
  return (
    isRecord(value) &&
    typeof value.diaryDate === 'string' &&
    /^\d{4}-\d{2}-\d{2}$/.test(value.diaryDate) &&
    typeof value.sourceText === 'string' &&
    value.sourceText.trim().length >= 1 &&
    value.sourceText.length <= 300
  );
};

const getCreateDiaryValidationErrors = (value: unknown): ValidationError[] => {
  if (!isRecord(value)) {
    return [
      {
        field: 'request',
        reason: '요청 본문 형식이 올바르지 않습니다.',
      },
    ];
  }

  const errors: ValidationError[] = [];

  if (
    typeof value.diaryDate !== 'string' ||
    !/^\d{4}-\d{2}-\d{2}$/.test(value.diaryDate)
  ) {
    errors.push({
      field: 'diaryDate',
      reason: '일기 날짜는 YYYY-MM-DD 형식이어야 합니다.',
    });
  }

  if (
    typeof value.sourceText !== 'string' ||
    value.sourceText.trim().length < 1 ||
    value.sourceText.length > 300
  ) {
    errors.push({
      field: 'sourceText',
      reason: '일기 내용은 1자 이상 300자 이하여야 합니다.',
    });
  }

  return errors;
};

const problemTitleByCode: Record<string, string> = {
  VALIDATION_ERROR: 'Validation failed',
  INVALID_IDEMPOTENCY_KEY: 'Invalid idempotency key',
  IDEMPOTENCY_KEY_CONFLICT: 'Idempotency key conflict',
  DIARY_NOT_FOUND: 'Diary not found',
  DUPLICATE_DIARY: 'Duplicate diary',
  DAILY_GENERATION_LIMIT_EXCEEDED: 'Daily generation limit exceeded',
};

const createProblemDetails = ({
  status,
  code,
  detail,
  errors,
  instance = '/api/v1/diaries',
}: {
  status: number;
  code: string;
  detail: string;
  errors?: ValidationError[];
  instance?: string;
}) => {
  return HttpResponse.json(
    {
      type: `https://api.harudle.example/problems/${code
        .toLowerCase()
        .replaceAll('_', '-')}`,
      title: problemTitleByCode[code] ?? code,
      status,
      detail,
      instance,
      code,
      traceId: '019d71beebed75b19e45f9c51863bcbd',
      ...(errors ? { errors } : {}),
    },
    {
      status,
      headers: {
        'Content-Type': 'application/problem+json',
      },
    },
  );
};

const createMockUuid = (sequence: number, suffix: number) => {
  return `00000000-0000-4000-8000-${String(sequence * 10 + suffix).padStart(
    12,
    '0',
  )}`;
};

const normalizeDiaryText = (sourceText: string) => {
  return sourceText.trim().replaceAll(/\s+/g, ' ');
};

const createDiaryFingerprint = ({
  diaryDate,
  sourceText,
}: CreateDiaryRequest) => {
  return `${diaryDate}:${normalizeDiaryText(sourceText)}`;
};

const isUuid = (value: string | null): value is string => {
  return (
    value !== null &&
    /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i.test(
      value,
    )
  );
};

const augustDiaries = [
  {
    date: '2026-08-12',
    exist: true,
    items: [
      {
        id: SAMPLE_DIARY_ID,
        title: '비가 와도, 나는 괜찮았다.',
        thumbnailUrl: diaryThumbnailUrl,
      },
    ],
  },
  {
    date: '2026-08-11',
    exist: true,
    items: [
      {
        id: createMockUuid(3, 1),
        title: '회사에서 칭찬받은 날!',
        thumbnailUrl: diaryThumbnailUrl,
      },
    ],
  },
  {
    date: '2026-08-10',
    exist: true,
    items: [
      {
        id: createMockUuid(4, 1),
        title: '오랜만에 친구들을 만났다.',
        thumbnailUrl: diaryThumbnailUrl,
      },
    ],
  },
  {
    date: '2026-08-09',
    exist: true,
    items: [
      {
        id: createMockUuid(5, 1),
        title: '새로운 아이디어가 떠올랐다.',
        thumbnailUrl: diaryThumbnailUrl,
      },
    ],
  },
  {
    date: '2026-08-08',
    exist: true,
    items: [
      {
        id: createMockUuid(6, 1),
        title: '아무것도 하지 않은 행복',
        thumbnailUrl: diaryThumbnailUrl,
      },
    ],
  },
  {
    date: '2026-08-06',
    exist: true,
    items: [
      {
        id: createMockUuid(7, 1),
        title: '비가 와도 나는 괜찮았다.',
        thumbnailUrl: diaryThumbnailUrl,
      },
    ],
  },
  {
    date: '2026-08-05',
    exist: false,
    items: [],
  },
];

// 홈 - 월간 일기 조회
export const handlers = [
  http.post('/api/v1/auth/logout', async () => {
    await delay(1_500);

    return new HttpResponse(null, { status: 204 });
  }),

  http.get('/api/v1/me', async () => {
    await delay(1_500);

    return HttpResponse.json({
      id: '08d69a34-6d70-4d42-a158-671bc67733c9',
      name: '하루들',
      email: 'harudle.official@gmail.com',
      oauthProviders: ['kakao'],
      createdAt: '2026-08-06T10:30:00+09:00',
    });
  }),

  http.get('/api/v1/diaries', async ({ request }) => {
    const url = new URL(request.url);
    const year = Number(url.searchParams.get('year'));
    const month = Number(url.searchParams.get('month'));

    await delay(1_500);

    return HttpResponse.json({
      year,
      month,
      days: year === 2026 && month === 8 ? augustDiaries : [],
    });
  }),

  http.get('/api/v1/me/generation-usage', async () => {
    await delay(1_500);

    return HttpResponse.json({
      usageDate: MOCK_USAGE_DATE,
      usedCount: usedGenerationCount,
      limitCount: DAILY_GENERATION_LIMIT,
      remainingCount: DAILY_GENERATION_LIMIT - usedGenerationCount,
    });
  }),

  http.get('/api/v1/diaries/:diaryId', async ({ params }) => {
    const diaryId = String(params.diaryId);
    const createdDiaryDetail = createdDiaryDetails.get(diaryId);
    const diaryDay = augustDiaries.find((day) =>
      day.items.some((diary) => diary.id === diaryId),
    );
    const diary = diaryDay?.items.find((item) => item.id === diaryId);

    await delay(1_500);

    if (createdDiaryDetail) {
      return HttpResponse.json(createdDiaryDetail);
    }

    if (!diaryDay || !diary) {
      return createProblemDetails({
        status: 404,
        code: 'DIARY_NOT_FOUND',
        detail: '일기를 찾을 수 없습니다.',
        instance: `/api/v1/diaries/${diaryId}`,
      });
    }

    const response: DiaryDetailResponse = {
      id: diaryId,
      diaryDate: diaryDay.date,
      sourceText: '오늘 친구와 카페에 가서 오래 이야기했다.',
      createdAt: `${diaryDay.date}T20:10:23+09:00`,
      generation: {
        id: createMockUuid(100, 2),
        status: 'SUCCEEDED',
        title: diary.title,
        imageUrl: diary.thumbnailUrl,
        imageUrlExpiresAt: `${diaryDay.date}T20:20:23+09:00`,
        completedAt: `${diaryDay.date}T20:11:42+09:00`,
      },
    };

    return HttpResponse.json(response);
  }),

  http.delete('/api/v1/diaries/:diaryId', async ({ params }) => {
    const diaryId = String(params.diaryId);

    await delay(1_500);

    for (const day of augustDiaries) {
      const diaryIndex = day.items.findIndex((diary) => diary.id === diaryId);

      if (diaryIndex !== -1) {
        day.items.splice(diaryIndex, 1);
        day.exist = day.items.length > 0;
        break;
      }
    }

    createdDiaryDetails.delete(diaryId);
    mockDiaryShareLinks.delete(diaryId);

    return new HttpResponse(null, { status: 204 });
  }),

  http.put(
    '/api/v1/diaries/:diaryId/share-link',
    async ({ params, request }) => {
      const diaryId = String(params.diaryId);
      const diaryExists =
        createdDiaryDetails.has(diaryId) ||
        augustDiaries.some((day) =>
          day.items.some((diary) => diary.id === diaryId),
        );

      await delay(1_500);

      if (!diaryExists) {
        return createProblemDetails({
          status: 404,
          code: 'DIARY_NOT_FOUND',
          detail: '일기를 찾을 수 없습니다.',
          instance: `/api/v1/diaries/${diaryId}/share-link`,
        });
      }

      const existingShareLink = mockDiaryShareLinks.get(diaryId);

      if (existingShareLink) {
        return HttpResponse.json(existingShareLink);
      }

      const shareId = createMockUuid(mockShareSequence, 3);
      mockShareSequence += 1;

      const response: DiaryShareLinkResponse = {
        shareId,
        shareUrl: `${new URL(request.url).origin}/shares/${shareId}`,
        createdAt: '2026-08-12T20:15:00+09:00',
      };

      mockDiaryShareLinks.set(diaryId, response);

      return HttpResponse.json(response, { status: 201 });
    },
  ),

  http.post('/api/v1/diaries', async ({ request }) => {
    await delay(1_500);

    const idempotencyKey = request.headers.get('Idempotency-Key');

    if (!isUuid(idempotencyKey)) {
      return createProblemDetails({
        status: 400,
        code: 'INVALID_IDEMPOTENCY_KEY',
        detail: 'Idempotency-Key는 UUID 형식의 필수 헤더입니다.',
      });
    }

    let requestBody: unknown;

    try {
      requestBody = await request.json();
    } catch {
      return createProblemDetails({
        status: 400,
        code: 'VALIDATION_ERROR',
        detail: '요청 본문이 올바른 JSON 형식이 아닙니다.',
      });
    }

    if (!isCreateDiaryRequest(requestBody)) {
      return createProblemDetails({
        status: 400,
        code: 'VALIDATION_ERROR',
        detail: '요청 값이 올바르지 않습니다.',
        errors: getCreateDiaryValidationErrors(requestBody),
      });
    }

    const diaryFingerprint = createDiaryFingerprint(requestBody);
    const existingRequest = createdDiaryRequests.get(idempotencyKey);

    if (existingRequest?.fingerprint === diaryFingerprint) {
      return HttpResponse.json(existingRequest.response);
    }

    if (existingRequest) {
      return createProblemDetails({
        status: 409,
        code: 'IDEMPOTENCY_KEY_CONFLICT',
        detail: '같은 Idempotency-Key를 다른 요청에 사용할 수 없습니다.',
      });
    }

    if (usedGenerationCount >= DAILY_GENERATION_LIMIT) {
      const response = createProblemDetails({
        status: 429,
        code: 'DAILY_GENERATION_LIMIT_EXCEEDED',
        detail: `하루 최대 ${DAILY_GENERATION_LIMIT}번까지 생성할 수 있습니다.`,
      });
      response.headers.set('Retry-After', '43200');
      return response;
    }

    const diarySequence = mockDiarySequence;
    mockDiarySequence += 1;
    usedGenerationCount += 1;

    const createdAt = `${requestBody.diaryDate}T20:10:23+09:00`;
    const completedAt = `${requestBody.diaryDate}T20:11:42+09:00`;
    const imageUrlExpiresAt = `${requestBody.diaryDate}T20:20:23+09:00`;
    const diaryId = createMockUuid(diarySequence, 1);

    const response: CreateDiaryResponse = {
      id: diaryId,
      diaryDate: requestBody.diaryDate,
      sourceText: requestBody.sourceText,
      createdAt,
      generation: {
        id: createMockUuid(diarySequence, 2),
        status: 'SUCCEEDED',
        title: '오늘 하루의 소중한 기록',
        imageUrl: diaryThumbnailUrl,
        imageUrlExpiresAt,
        completedAt,
      },
      usage: {
        usageDate: MOCK_USAGE_DATE,
        usedCount: usedGenerationCount,
        limitCount: DAILY_GENERATION_LIMIT,
        remainingCount: DAILY_GENERATION_LIMIT - usedGenerationCount,
      },
    };

    createdDiaryRequests.set(idempotencyKey, {
      fingerprint: diaryFingerprint,
      response,
    });
    createdDiaryDetails.set(diaryId, {
      id: response.id,
      diaryDate: response.diaryDate,
      sourceText: response.sourceText,
      createdAt: response.createdAt,
      generation: response.generation,
    });

    return HttpResponse.json(response, {
      status: 201,
      headers: {
        Location: `/api/v1/diaries/${diaryId}`,
      },
    });
  }),
];
