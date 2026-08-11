import { delay, http, HttpResponse } from 'msw';

interface CreateDiaryRequest {
  diaryDate: string;
  sourceText: string;
}

const MOCK_USAGE_DATE = '2026-08-12';
const DAILY_GENERATION_LIMIT = 3;
const UUID_PATTERN =
  /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;

let usedGenerationCount = 0;
let mockDiarySequence = 1;

const diaryThumbnailUrl = new URL(
  '../assets/images/diary-four-panel.png',
  import.meta.url,
).href;

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

const createProblemDetails = ({
  status,
  code,
  detail,
}: {
  status: number;
  code: string;
  detail: string;
}) => {
  return HttpResponse.json(
    {
      type: `https://api.harudle.example/problems/${code
        .toLowerCase()
        .replaceAll('_', '-')}`,
      title: code,
      status,
      detail,
      instance: '/api/v1/diaries',
      code,
      traceId: '019d71beebed75b19e45f9c51863bcbd',
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

const augustDiaries = [
  { date: '2026-08-12', title: '비가 와도, 나는 괜찮았다.' },
  { date: '2026-08-11', title: '회사에서 칭찬받은 날!' },
  { date: '2026-08-10', title: '오랜만에 친구들을 만났다.' },
  { date: '2026-08-09', title: '새로운 아이디어가 떠올랐다.' },
  { date: '2026-08-08', title: '아무것도 하지 않은 행복' },
  { date: '2026-08-07', title: '주말엔 영화와 팝콘!' },
].map(({ date, title }) => ({
  date,
  exist: true,
  title,
  thumbnailUrl: diaryThumbnailUrl,
}));

export const handlers = [
  http.get('/api/v1/diaries', ({ request }) => {
    const url = new URL(request.url);
    const year = Number(url.searchParams.get('year'));
    const month = Number(url.searchParams.get('month'));

    return HttpResponse.json({
      year,
      month,
      days: year === 2026 && month === 8 ? augustDiaries : [],
    });
  }),

  http.get('/api/v1/me/generation-usage', () => {
    return HttpResponse.json({
      usageDate: MOCK_USAGE_DATE,
      usedCount: usedGenerationCount,
      limitCount: DAILY_GENERATION_LIMIT,
      remainingCount: DAILY_GENERATION_LIMIT - usedGenerationCount,
    });
  }),

  http.post('/api/v1/diaries', async ({ request }) => {
    const idempotencyKey = request.headers.get('Idempotency-Key');

    if (!idempotencyKey || !UUID_PATTERN.test(idempotencyKey)) {
      return createProblemDetails({
        status: 400,
        code: 'INVALID_IDEMPOTENCY_KEY',
        detail: '유효한 UUID 형식의 Idempotency-Key 헤더가 필요합니다.',
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
        detail: '일기 날짜와 1자 이상 300자 이하의 일기 내용을 확인해주세요.',
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

    const response = {
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

    await delay(1_000);

    return HttpResponse.json(response, {
      status: 201,
      headers: {
        Location: `/api/v1/diaries/${diaryId}`,
      },
    });
  }),
];
