import { delay, http, HttpResponse } from 'msw';
import { reportHandlers } from './reportHandlers';
import { isLandingPath } from '../pages/landing/landingPaths';
import { MOCK_SCENARIO_HEADER, MOCK_SCENARIOS } from './mockScenarios';
import { legalHandlers } from './legalHandlers';
import {
  isMockSessionLoggedOut,
  markMockSessionLoggedOut,
  resetMockSession,
} from './mockAuthSession';
import {
  createMockGenerationJob,
  getMockGeneration,
  getMockGenerationStatus,
  MOCK_GENERATION_COMPLETION_MS,
  type MockGenerationJob,
  type MockSucceededGeneration,
  type MockDiaryGeneration,
} from './diaryGeneration';
import { isCanonicalUuid } from '../pages/landing/guestTrialUuid';
import { getToday } from '../shared/utils';
import diaryImage from '../assets/images/diary-four-panel.webp';
import { getMockPublishedFeedId, hasMockFeed } from './feedHandlers';

interface CreateDiaryRequest {
  diaryDate: string;
  sourceText: string;
}

interface CreateDiaryResponse {
  id: string;
  diaryDate: string;
  sourceText: string;
  createdAt: string;
  generation: MockSucceededGeneration;
  usage: {
    usageDate: string;
    usedCount: number;
    limitCount: number;
    remainingCount: number;
  };
}

interface DiaryDetailResponse {
  id: string;
  publishedFeedId: string | null;
  diaryDate: string;
  sourceText: string;
  createdAt: string;
  generation: MockDiaryGeneration;
}

interface ValidationError {
  field: string;
  reason: string;
}

const mockToday = getToday();
const mockYearMonth = `${mockToday.year}-${String(mockToday.month).padStart(2, '0')}`;
const getMockDate = (day: number): string =>
  `${mockYearMonth}-${String(day).padStart(2, '0')}`;
const MOCK_USAGE_DATE = getMockDate(mockToday.day);
const DAILY_GENERATION_LIMIT = 3;
const MOCK_ACCESS_TOKEN = 'mock-access-token';
const MOCK_CSRF_TOKEN = 'mock-csrf-token';
const RESUMABLE_GENERATION_STORAGE_KEY = 'harudle:mock:resumable-generation:v1';

let usedGenerationCount = 0;
let mockDiarySequence = 1_000;

const createdDiaryRequests = new Map<
  string,
  {
    fingerprint: string;
    response: CreateDiaryResponse;
    job: MockGenerationJob;
    usageRestored: boolean;
  }
>();
const createdDiaryDetails = new Map<string, DiaryDetailResponse>();
const deletedDiaryIds = new Set<string>();
const failedStatusRequestIds = new Set<string>();
const delayedStatusRequestIds = new Set<string>();

const diaryThumbnailUrl = diaryImage;

const SAMPLE_DIARY_ID = '00000000-0000-4000-8000-000000000001';
const PUBLISHED_DIARY_ID = '00000000-0000-4000-8000-000000000031';
const SAMPLE_PUBLISHED_FEED_ID = '00000000-0000-4000-8000-000000000001';

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
  GENERATION_NOT_FOUND: 'Generation not found',
  DUPLICATE_DIARY: 'Duplicate diary',
  DAILY_GENERATION_LIMIT_EXCEEDED: 'Daily generation limit exceeded',
  DIARY_GENERATION_FAILED: 'Diary generation failed',
  AI_PROVIDER_ERROR: 'AI provider error',
  GENERATION_UNAVAILABLE: 'Generation unavailable',
  DIARY_DETAIL_REQUEST_FAILED: 'Diary detail request failed',
  DIARY_DELETE_FAILED: 'Diary delete failed',
  PROFILE_REQUEST_FAILED: 'Profile request failed',
  LOGOUT_FAILED: 'Logout failed',
  INVALID_REFRESH_TOKEN: 'Invalid refresh token',
  INVALID_CURRENT_USER: 'Invalid current user',
  UNAUTHORIZED: 'Unauthorized',
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

const validateAccessToken = (request: Request) => {
  if (request.headers.get('Authorization') === `Bearer ${MOCK_ACCESS_TOKEN}`) {
    return null;
  }

  const response = createProblemDetails({
    status: 401,
    code: 'UNAUTHORIZED',
    detail: '인증 정보가 필요합니다.',
    instance: new URL(request.url).pathname,
  });
  response.headers.set('WWW-Authenticate', 'Bearer');

  return response;
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

const createMockDiaryResponse = (
  body: CreateDiaryRequest,
  job: MockGenerationJob,
  diarySequence: number,
  usedCount: number,
): CreateDiaryResponse => ({
  id: createMockUuid(diarySequence, 1),
  diaryDate: body.diaryDate,
  sourceText: body.sourceText,
  createdAt: new Date(job.acceptedAt).toISOString(),
  generation: {
    id: createMockUuid(diarySequence, 2),
    status: 'SUCCEEDED',
    title: '오늘 하루의 소중한 기록',
    imageUrl: diaryThumbnailUrl,
    imageUrlExpiresAt: new Date(job.acceptedAt + 3_600_000).toISOString(),
    completedAt: new Date(
      job.acceptedAt + MOCK_GENERATION_COMPLETION_MS,
    ).toISOString(),
  },
  usage: {
    usageDate: MOCK_USAGE_DATE,
    usedCount,
    limitCount: DAILY_GENERATION_LIMIT,
    remainingCount: DAILY_GENERATION_LIMIT - usedCount,
  },
});

interface ResumableGeneration {
  readonly idempotencyKey: string;
  readonly requestBody: CreateDiaryRequest;
  readonly acceptedAt: number;
  readonly diarySequence: number;
  readonly usedCount: number;
}

const isResumableGeneration = (
  value: unknown,
): value is ResumableGeneration => {
  return (
    isRecord(value) &&
    typeof value.idempotencyKey === 'string' &&
    isUuid(value.idempotencyKey) &&
    isCreateDiaryRequest(value.requestBody) &&
    typeof value.acceptedAt === 'number' &&
    Number.isFinite(value.acceptedAt) &&
    value.acceptedAt > 0 &&
    !Number.isNaN(new Date(value.acceptedAt + 3_600_000).getTime()) &&
    typeof value.diarySequence === 'number' &&
    Number.isSafeInteger(value.diarySequence) &&
    value.diarySequence >= 1_000 &&
    value.diarySequence < 10_000_000_000 &&
    typeof value.usedCount === 'number' &&
    Number.isInteger(value.usedCount) &&
    value.usedCount >= 1 &&
    value.usedCount <= DAILY_GENERATION_LIMIT
  );
};

const getMockSessionStorage = (): Storage | null => {
  try {
    return globalThis.sessionStorage ?? null;
  } catch {
    return null;
  }
};

const restoreResumableGeneration = (): void => {
  try {
    const saved = getMockSessionStorage()?.getItem(
      RESUMABLE_GENERATION_STORAGE_KEY,
    );
    if (!saved) return;
    const snapshot: unknown = JSON.parse(saved);
    if (!isResumableGeneration(snapshot)) return;

    const {
      idempotencyKey,
      requestBody,
      acceptedAt,
      diarySequence,
      usedCount,
    } = snapshot;
    const job = { acceptedAt, shouldFail: false };
    const response = createMockDiaryResponse(
      requestBody,
      job,
      diarySequence,
      usedCount,
    );
    createdDiaryRequests.set(idempotencyKey, {
      fingerprint: createDiaryFingerprint(requestBody),
      response,
      job,
      usageRestored: false,
    });
    usedGenerationCount = usedCount;
    mockDiarySequence = diarySequence + 1;
  } catch {
    // 저장된 모킹 데이터가 잘못되어도 기본 시나리오는 계속 사용한다.
  }
};

const mockDiaryDays = [
  {
    date: getMockDate(12),
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
    date: getMockDate(11),
    exist: true,
    items: [
      {
        id: PUBLISHED_DIARY_ID,
        title: '회사에서 칭찬받은 날!',
        thumbnailUrl: diaryThumbnailUrl,
      },
    ],
  },
  {
    date: getMockDate(10),
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
    date: getMockDate(9),
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
    date: getMockDate(8),
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
    date: getMockDate(6),
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
    date: getMockDate(5),
    exist: false,
    items: [],
  },
];

const addDiaryToMockDiaries = (diary: CreateDiaryResponse): void => {
  const diaryItem = {
    id: diary.id,
    title: diary.generation.title,
    thumbnailUrl: diary.generation.imageUrl,
  };
  const diaryDay = mockDiaryDays.find((day) => day.date === diary.diaryDate);

  if (diaryDay) {
    diaryDay.exist = true;
    diaryDay.items.unshift(diaryItem);
    return;
  }

  mockDiaryDays.push({
    date: diary.diaryDate,
    exist: true,
    items: [diaryItem],
  });
  mockDiaryDays.sort((a, b) => b.date.localeCompare(a.date));
};

// 폴링을 건너뛰어도 완료된 일기는 목록과 상세 조회에 반영한다.
const syncCreatedDiaries = (): void => {
  for (const record of createdDiaryRequests.values()) {
    const { response, job } = record;
    const generation = getMockGeneration(job, response.generation);
    if (generation.status === 'FAILED' && !record.usageRestored) {
      usedGenerationCount -= 1;
      record.usageRestored = true;
    }
    if (
      deletedDiaryIds.has(response.id) ||
      generation.status !== 'SUCCEEDED' ||
      createdDiaryDetails.has(response.id)
    ) {
      continue;
    }
    createdDiaryDetails.set(response.id, {
      id: response.id,
      publishedFeedId: null,
      diaryDate: response.diaryDate,
      sourceText: response.sourceText,
      createdAt: response.createdAt,
      generation,
    });
    addDiaryToMockDiaries(response);
  }
};

export const resetDiaryGenerationMockState = (): void => {
  const createdIds = new Set(
    Array.from(createdDiaryRequests.values(), ({ response }) => response.id),
  );
  for (const day of mockDiaryDays) {
    day.items = day.items.filter(({ id }) => !createdIds.has(id));
    day.exist = day.items.length > 0;
  }
  createdDiaryRequests.clear();
  createdDiaryDetails.clear();
  deletedDiaryIds.clear();
  failedStatusRequestIds.clear();
  delayedStatusRequestIds.clear();
  getMockSessionStorage()?.removeItem(RESUMABLE_GENERATION_STORAGE_KEY);
  usedGenerationCount = 0;
  mockDiarySequence = 1_000;
};

// 홈 - 월간 일기 조회
let mockNickname = '하루들';
let mockProfileImageUrl = '/profile-images/v1/5.png';

// 새로고침 재개 시나리오에서 저장한 생성 요청만 복원한다.
restoreResumableGeneration();

export const handlers = [
  ...reportHandlers,
  http.get('/oauth2/authorization/kakao', async ({ request }) => {
    resetMockSession();
    await delay(300);

    if (
      request.headers.get(MOCK_SCENARIO_HEADER) ===
      MOCK_SCENARIOS.oauthAuthorization
    ) {
      return HttpResponse.html('<!doctype html><title>카카오 OAuth</title>');
    }

    return HttpResponse.redirect(new URL('/auth/callback', request.url));
  }),

  http.get('/api/v1/auth/csrf', async () => {
    await delay(300);

    return HttpResponse.json(
      {
        token: MOCK_CSRF_TOKEN,
      },
      {
        headers: {
          'Cache-Control': 'no-store',
        },
      },
    );
  }),

  http.post('/api/v1/auth/refresh', async ({ request }) => {
    await delay(300);
    if (globalThis.location.pathname === '/auth/callback') resetMockSession();

    if (
      request.headers.get(MOCK_SCENARIO_HEADER) ===
        MOCK_SCENARIOS.authRefreshFailure ||
      isMockSessionLoggedOut()
    ) {
      return createProblemDetails({
        status: 401,
        code: 'INVALID_REFRESH_TOKEN',
        detail: '유효한 Refresh Token이 없습니다.',
        instance: '/api/v1/auth/refresh',
      });
    }

    if (
      isLandingPath(globalThis.location.pathname) &&
      request.headers.get(MOCK_SCENARIO_HEADER) !==
        MOCK_SCENARIOS.authRefreshSuccess
    ) {
      return createProblemDetails({
        status: 401,
        code: 'INVALID_REFRESH_TOKEN',
        detail: '유효한 Refresh Token이 없습니다.',
        instance: '/api/v1/auth/refresh',
      });
    }

    return HttpResponse.json(
      {
        accessToken: MOCK_ACCESS_TOKEN,
        tokenType: 'Bearer',
        expiresIn: 1800,
      },
      {
        headers: {
          'Cache-Control': 'no-store',
        },
      },
    );
  }),

  http.post('/api/v1/auth/logout', async ({ request }) => {
    if (request.headers.get('X-XSRF-TOKEN') !== MOCK_CSRF_TOKEN)
      return createProblemDetails({
        status: 403,
        code: 'INVALID_CSRF_TOKEN',
        detail: 'CSRF Token이 올바르지 않아요.',
        instance: '/api/v1/auth/logout',
      });

    await delay(300);

    if (
      request.headers.get(MOCK_SCENARIO_HEADER) === MOCK_SCENARIOS.logoutFailure
    ) {
      return createProblemDetails({
        status: 503,
        code: 'LOGOUT_FAILED',
        detail: '로그아웃에 실패했습니다. 다시 시도해주세요.',
        instance: '/api/v1/auth/logout',
      });
    }

    markMockSessionLoggedOut();
    return new HttpResponse(null, {
      status: 204,
      headers: {
        'Cache-Control': 'no-store',
      },
    });
  }),

  http.get('/api/v1/me', async ({ request }) => {
    const unauthorizedResponse = validateAccessToken(request);

    if (unauthorizedResponse) {
      return unauthorizedResponse;
    }

    await delay(300);

    if (
      request.headers.get(MOCK_SCENARIO_HEADER) ===
      MOCK_SCENARIOS.profileFailure
    ) {
      return createProblemDetails({
        status: 503,
        code: 'PROFILE_REQUEST_FAILED',
        detail: '설정 정보를 불러오지 못했습니다. 다시 시도해주세요.',
        instance: '/api/v1/me',
      });
    }

    return HttpResponse.json(
      {
        id: '08d69a34-6d70-4d42-a158-671bc67733c9',
        name: '하루들',
        nickname: mockNickname,
        profileImageUrl: mockProfileImageUrl,
        email: 'harudle.official@gmail.com',
        role: 'ADMIN',
        oauthProviders: ['kakao'],
        createdAt: '2026-08-06T10:30:00+09:00',
      },
      {
        headers: {
          'Cache-Control': 'no-store',
        },
      },
    );
  }),

  http.patch('/api/v1/me/profile', async ({ request }) => {
    const unauthorizedResponse = validateAccessToken(request);
    if (unauthorizedResponse) return unauthorizedResponse;

    await delay(300);
    const data = await request.formData();
    const nickname = data.get('nickname');
    const imageCode = data.get('imageCode');
    const image = data.get('image');
    const problem = (
      status: number,
      code: string,
      detail: string,
      errors?: ValidationError[],
    ): Response =>
      createProblemDetails({
        status,
        code,
        detail,
        errors,
        instance: '/api/v1/me/profile',
      });

    if ((!nickname && !imageCode && !image) || (imageCode && image)) {
      return problem(
        400,
        'VALIDATION_ERROR',
        '변경할 프로필 정보를 확인해 주세요.',
      );
    }
    if (typeof nickname === 'string') {
      if (!nickname.trim() || Array.from(nickname).length > 10) {
        return problem(400, 'VALIDATION_ERROR', '입력값을 확인해 주세요.', [
          {
            field: 'nickname',
            reason: '닉네임은 1자 이상 10자 이하로 입력해 주세요.',
          },
        ]);
      }
      if (
        !/^[가-힣ㄱ-ㅎㅏ-ㅣA-Za-z0-9 ]+$/.test(nickname) ||
        nickname !== nickname.trim() ||
        nickname.includes('  ')
      ) {
        return problem(400, 'VALIDATION_ERROR', '입력값을 확인해 주세요.', [
          {
            field: 'nickname',
            reason: '닉네임의 문자와 공백을 확인해 주세요.',
          },
        ]);
      }
      if (nickname !== mockNickname && ['네오', '하루들2'].includes(nickname)) {
        return problem(
          409,
          'NICKNAME_ALREADY_IN_USE',
          '이미 사용 중인 닉네임이에요.',
        );
      }
    }
    if (imageCode !== null && !/^(10|[1-9])$/.test(String(imageCode))) {
      return problem(
        400,
        'VALIDATION_ERROR',
        '프로필 이미지 번호를 확인해 주세요.',
      );
    }
    if (image instanceof File) {
      if (image.size > 5_000_000)
        return problem(413, 'HTTP_413', '프로필 이미지는 5MB 이하여야 합니다.');
      if (!['image/jpeg', 'image/png'].includes(image.type))
        return problem(
          415,
          'HTTP_415',
          '프로필 이미지는 JPEG 또는 PNG만 지원합니다.',
        );
      if (!image.size)
        return problem(
          400,
          'VALIDATION_ERROR',
          '프로필 이미지 파일을 읽을 수 없습니다.',
        );
    }

    // 모든 검증을 통과한 뒤 두 값을 함께 반영한다. Mock에서는 이미지 변환을 하지 않는다.
    if (typeof nickname === 'string') mockNickname = nickname;
    if (imageCode !== null || image instanceof File) {
      if (mockProfileImageUrl.startsWith('blob:'))
        URL.revokeObjectURL(mockProfileImageUrl);
      mockProfileImageUrl =
        image instanceof File
          ? URL.createObjectURL(image)
          : `/profile-images/v1/${String(imageCode)}.png`;
    }
    return new HttpResponse(null, { status: 204 });
  }),

  http.patch('/api/v1/me/nickname', async ({ request }) => {
    const unauthorizedResponse = validateAccessToken(request);
    if (unauthorizedResponse) return unauthorizedResponse;

    await delay(300);
    const data: unknown = await request.json();
    const nickname =
      typeof data === 'object' &&
      data !== null &&
      'nickname' in data &&
      typeof data.nickname === 'string'
        ? data.nickname.trim()
        : '';

    if (
      nickname !== mockNickname &&
      (nickname.length === 0 || Array.from(nickname).length > 10)
    ) {
      return createProblemDetails({
        status: 400,
        code: 'VALIDATION_ERROR',
        detail: '닉네임은 1자 이상 10자 이하로 입력해 주세요.',
        instance: '/api/v1/me/nickname',
      });
    }

    if (
      nickname !== mockNickname &&
      (nickname === '네오' || nickname === '하루들2')
    ) {
      return createProblemDetails({
        status: 409,
        code: 'NICKNAME_ALREADY_IN_USE',
        detail: '이미 사용 중인 닉네임이에요.',
        instance: '/api/v1/me/nickname',
      });
    }

    mockNickname = nickname;
    return new HttpResponse(null, { status: 204 });
  }),

  http.get('/api/v1/diaries', async ({ request }) => {
    syncCreatedDiaries();
    const unauthorizedResponse = validateAccessToken(request);

    if (unauthorizedResponse) {
      return unauthorizedResponse;
    }

    if (
      request.headers.get(MOCK_SCENARIO_HEADER) ===
      MOCK_SCENARIOS.monthlyDiariesNonJsonError
    ) {
      return HttpResponse.html('<html><body>Bad Gateway</body></html>', {
        status: 502,
      });
    }

    const url = new URL(request.url);
    const year = Number(url.searchParams.get('year'));
    const month = Number(url.searchParams.get('month'));

    await delay(1_500);

    return HttpResponse.json({
      year,
      month,
      days: mockDiaryDays.filter((day) =>
        day.date.startsWith(`${year}-${String(month).padStart(2, '0')}-`),
      ),
    });
  }),

  http.get('/api/v1/me/generation-usage', async ({ request }) => {
    syncCreatedDiaries();
    const unauthorizedResponse = validateAccessToken(request);

    if (unauthorizedResponse) {
      return unauthorizedResponse;
    }

    await delay(1_500);

    return HttpResponse.json({
      usageDate: MOCK_USAGE_DATE,
      usedCount: usedGenerationCount,
      limitCount: DAILY_GENERATION_LIMIT,
      remainingCount: DAILY_GENERATION_LIMIT - usedGenerationCount,
    });
  }),

  http.get('/api/v1/diaries/:diaryId', async ({ params, request }) => {
    const unauthorizedResponse = validateAccessToken(request);

    if (unauthorizedResponse) {
      return unauthorizedResponse;
    }

    syncCreatedDiaries();
    const diaryId = String(params.diaryId);
    const pendingDiary = Array.from(createdDiaryRequests.values()).find(
      ({ response }) =>
        response.id === diaryId && !deletedDiaryIds.has(diaryId),
    );
    const createdDiaryDetail = createdDiaryDetails.get(diaryId);
    const diaryDay = mockDiaryDays.find((day) =>
      day.items.some((diary) => diary.id === diaryId),
    );
    const diary = diaryDay?.items.find((item) => item.id === diaryId);

    await delay(1_500);

    if (
      request.headers.get(MOCK_SCENARIO_HEADER) ===
      MOCK_SCENARIOS.diaryDetailFailure
    ) {
      return createProblemDetails({
        status: 503,
        code: 'DIARY_DETAIL_REQUEST_FAILED',
        detail: '일기 상세 정보를 불러오지 못했습니다. 다시 시도해주세요.',
        instance: `/api/v1/diaries/${diaryId}`,
      });
    }

    if (pendingDiary && !createdDiaryDetail) {
      const generation = getMockGeneration(
        pendingDiary.job,
        pendingDiary.response.generation,
      );
      if (generation.status !== 'FAILED') {
        const diary = pendingDiary.response;
        return HttpResponse.json({
          id: diary.id,
          diaryDate: diary.diaryDate,
          sourceText: diary.sourceText,
          createdAt: diary.createdAt,
          publishedFeedId: null,
          generation,
        });
      }
    }

    if (createdDiaryDetail) {
      return HttpResponse.json({
        ...createdDiaryDetail,
        publishedFeedId: getMockPublishedFeedId(diaryId),
      });
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
      publishedFeedId:
        getMockPublishedFeedId(diaryId) ??
        (diaryId === PUBLISHED_DIARY_ID && hasMockFeed(SAMPLE_PUBLISHED_FEED_ID)
          ? SAMPLE_PUBLISHED_FEED_ID
          : null),
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

    const scenario = request.headers.get(MOCK_SCENARIO_HEADER);
    if (scenario === MOCK_SCENARIOS.diaryLongKoreanTitle) {
      response.generation.title = '가'.repeat(100);
    } else if (scenario === MOCK_SCENARIOS.diaryLongEmojiTitle) {
      response.generation.title = '😀'.repeat(100);
    }

    return HttpResponse.json(response);
  }),

  http.delete('/api/v1/diaries/:diaryId', async ({ params, request }) => {
    const unauthorizedResponse = validateAccessToken(request);

    if (unauthorizedResponse) {
      return unauthorizedResponse;
    }

    const diaryId = String(params.diaryId);

    await delay(1_500);

    if (
      request.headers.get(MOCK_SCENARIO_HEADER) ===
      MOCK_SCENARIOS.diaryDeleteFailure
    ) {
      return createProblemDetails({
        status: 503,
        code: 'DIARY_DELETE_FAILED',
        detail: '일기 삭제에 실패했습니다. 다시 시도해주세요.',
        instance: `/api/v1/diaries/${diaryId}`,
      });
    }

    for (const day of mockDiaryDays) {
      const diaryIndex = day.items.findIndex((diary) => diary.id === diaryId);

      if (diaryIndex !== -1) {
        day.items.splice(diaryIndex, 1);
        day.exist = day.items.length > 0;
        break;
      }
    }

    createdDiaryDetails.delete(diaryId);
    deletedDiaryIds.add(diaryId);

    return new HttpResponse(null, { status: 204 });
  }),

  http.post('/api/v1/diaries', async ({ request }) => {
    const unauthorizedResponse = validateAccessToken(request);

    if (unauthorizedResponse) {
      return unauthorizedResponse;
    }

    await delay(300);

    if (
      request.headers.get(MOCK_SCENARIO_HEADER) ===
      MOCK_SCENARIOS.diaryGenerationRequestFailure
    ) {
      return createProblemDetails({
        status: 503,
        code: 'GENERATION_UNAVAILABLE',
        detail: '네컷만화 생성 요청을 접수하지 못했어요.',
      });
    }

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

    syncCreatedDiaries();
    const diaryFingerprint = createDiaryFingerprint(requestBody);
    const existingRequest = createdDiaryRequests.get(idempotencyKey);

    if (existingRequest?.fingerprint === diaryFingerprint) {
      if (deletedDiaryIds.has(existingRequest.response.id)) {
        return createProblemDetails({
          status: 404,
          code: 'DIARY_NOT_FOUND',
          detail: '일기를 찾을 수 없습니다.',
        });
      }
      if (
        getMockGeneration(
          existingRequest.job,
          existingRequest.response.generation,
        ).status === 'FAILED'
      ) {
        return createProblemDetails({
          status: 502,
          code: 'AI_PROVIDER_ERROR',
          detail: '이미지 생성 요청에 실패했습니다.',
        });
      }
      return HttpResponse.json({
        ...existingRequest.response,
        generation: getMockGeneration(
          existingRequest.job,
          existingRequest.response.generation,
        ),
      });
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

    const job = createMockGenerationJob(request);
    const response = createMockDiaryResponse(
      requestBody,
      job,
      diarySequence,
      usedGenerationCount,
    );

    createdDiaryRequests.set(idempotencyKey, {
      fingerprint: diaryFingerprint,
      response,
      job,
      usageRestored: false,
    });

    if (
      request.headers.get(MOCK_SCENARIO_HEADER) ===
      MOCK_SCENARIOS.diaryGenerationResume
    ) {
      getMockSessionStorage()?.setItem(
        RESUMABLE_GENERATION_STORAGE_KEY,
        JSON.stringify({
          idempotencyKey,
          requestBody,
          acceptedAt: job.acceptedAt,
          diarySequence,
          usedCount: usedGenerationCount,
        } satisfies ResumableGeneration),
      );
    }

    return HttpResponse.json(
      {
        ...response,
        generation: getMockGeneration(job, response.generation),
      },
      {
        status: 202,
        headers: {
          Location: `/api/v1/diaries/${response.id}`,
        },
      },
    );
  }),

  http.get(
    '/api/v1/diary-generations/:generationId',
    async ({ request, params }) => {
      const unauthorizedResponse = validateAccessToken(request);
      if (unauthorizedResponse) return unauthorizedResponse;

      await delay(150);
      const generationId = String(params.generationId);
      const instance = `/api/v1/diary-generations/${generationId}`;
      if (!isCanonicalUuid(generationId)) {
        return createProblemDetails({
          status: 400,
          code: 'VALIDATION_ERROR',
          detail: '생성 ID는 UUID 형식이어야 합니다.',
          instance,
        });
      }

      const record = Array.from(createdDiaryRequests.values()).find(
        ({ response }) => response.generation.id === generationId,
      );
      const scenario = request.headers.get(MOCK_SCENARIO_HEADER);
      if (
        scenario === MOCK_SCENARIOS.diaryGenerationNotFound ||
        !record ||
        deletedDiaryIds.has(record.response.id)
      ) {
        return createProblemDetails({
          status: 404,
          code: 'GENERATION_NOT_FOUND',
          detail: '생성 작업을 찾을 수 없습니다.',
          instance,
        });
      }

      if (
        scenario === MOCK_SCENARIOS.diaryGenerationStatusFailureOnce &&
        !failedStatusRequestIds.has(generationId)
      ) {
        failedStatusRequestIds.add(generationId);
        return createProblemDetails({
          status: 503,
          code: 'GENERATION_UNAVAILABLE',
          detail: '생성 상태를 잠시 불러올 수 없습니다.',
          instance,
        });
      }

      if (
        (scenario === MOCK_SCENARIOS.diaryGenerationOutOfOrder ||
          scenario === MOCK_SCENARIOS.diaryGenerationDelayedSuccess ||
          scenario === MOCK_SCENARIOS.diaryGenerationDelayedFailure ||
          scenario === MOCK_SCENARIOS.diaryGenerationDelayedNotFound) &&
        !delayedStatusRequestIds.has(generationId)
      ) {
        // 첫 응답을 다음 조회의 완료 및 새 작업 시작보다 늦게 보낸다.
        delayedStatusRequestIds.add(generationId);
        await delay(10_000);
        if (scenario === MOCK_SCENARIOS.diaryGenerationDelayedNotFound) {
          return createProblemDetails({
            status: 404,
            code: 'GENERATION_NOT_FOUND',
            detail: '생성 작업을 찾을 수 없습니다.',
            instance,
          });
        }

        const status =
          scenario === MOCK_SCENARIOS.diaryGenerationDelayedSuccess
            ? 'SUCCEEDED'
            : scenario === MOCK_SCENARIOS.diaryGenerationDelayedFailure
              ? 'FAILED'
              : 'PROCESSING';
        return HttpResponse.json(
          {
            id: generationId,
            status,
            diaryId: status === 'SUCCEEDED' ? record.response.id : null,
            errorCode: status === 'FAILED' ? 'AI_PROVIDER_ERROR' : null,
          },
          { headers: { 'Cache-Control': 'no-store' } },
        );
      }

      syncCreatedDiaries();
      const generation = getMockGeneration(
        record.job,
        record.response.generation,
      );
      return HttpResponse.json(
        getMockGenerationStatus(generation, record.response.id),
        {
          headers: { 'Cache-Control': 'no-store' },
        },
      );
    },
  ),
  ...legalHandlers,
];
