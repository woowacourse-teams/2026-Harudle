import { delay, http, HttpResponse } from 'msw';
import diaryImage from '../assets/images/diary-four-panel.webp';
import {
  createMockGenerationJob,
  getMockGeneration,
  getMockGenerationStatus,
  MOCK_GENERATION_COMPLETION_MS,
  type MockGenerationJob,
  type MockSucceededGeneration,
} from './diaryGeneration';
import { isCanonicalUuid } from '../pages/landing/guestTrialUuid';

interface GuestDiaryRequest {
  diaryDate: string;
  sourceText: string;
}

interface GuestDiaryResponse {
  id: string;
  diaryDate: string;
  sourceText: string;
  createdAt: string;
  generation: MockSucceededGeneration;
}

interface GuestDiaryRecord {
  fingerprint: string;
  job: MockGenerationJob;
  response: GuestDiaryResponse;
}

interface GuestTrialMockState {
  sessionIssued: boolean;
  trialUsed: boolean;
  nextDiarySequence: number;
  requests: Record<string, GuestDiaryRecord>;
  diaries: Record<string, GuestDiaryResponse>;
}

interface ProblemDetailsOptions {
  status: number;
  code: string;
  title: string;
  detail: string;
  instance: string;
}

export const GUEST_TRIAL_MOCK_CSRF_TOKEN = 'mock-csrf-token';

const GUEST_TRIAL_STATE_STORAGE_KEY = 'harudle:mock:guest-trial:v2';
const MOCK_TRACE_ID = '019d71beebed75b19e45f9c51863bcbd';

const createInitialState = (): GuestTrialMockState => ({
  sessionIssued: false,
  trialUsed: false,
  nextDiarySequence: 1,
  requests: {},
  diaries: {},
});

let fallbackState = createInitialState();
let guestDiaryCreationQueue: Promise<void> = Promise.resolve();

const isRecord = (value: unknown): value is Record<string, unknown> => {
  return typeof value === 'object' && value !== null;
};

const isGuestTrialMockState = (
  value: unknown,
): value is GuestTrialMockState => {
  return (
    isRecord(value) &&
    typeof value.sessionIssued === 'boolean' &&
    typeof value.trialUsed === 'boolean' &&
    typeof value.nextDiarySequence === 'number' &&
    isRecord(value.requests) &&
    isRecord(value.diaries)
  );
};

const getSessionStorage = (): Storage | null => {
  try {
    return globalThis.sessionStorage ?? null;
  } catch {
    return null;
  }
};

const loadState = (): GuestTrialMockState => {
  const storage = getSessionStorage();

  if (!storage) {
    return fallbackState;
  }

  const storedState = storage.getItem(GUEST_TRIAL_STATE_STORAGE_KEY);

  if (!storedState) {
    return createInitialState();
  }

  try {
    const parsedState: unknown = JSON.parse(storedState);
    return isGuestTrialMockState(parsedState)
      ? parsedState
      : createInitialState();
  } catch {
    return createInitialState();
  }
};

const saveState = (state: GuestTrialMockState): void => {
  const storage = getSessionStorage();

  if (storage) {
    storage.setItem(GUEST_TRIAL_STATE_STORAGE_KEY, JSON.stringify(state));
    return;
  }

  fallbackState = state;
};

export const resetGuestTrialMockState = (): void => {
  fallbackState = createInitialState();
  getSessionStorage()?.removeItem(GUEST_TRIAL_STATE_STORAGE_KEY);
};

const createProblemDetails = ({
  status,
  code,
  title,
  detail,
  instance,
}: ProblemDetailsOptions) => {
  return HttpResponse.json(
    {
      type: `https://api.harudle.example/problems/${code
        .toLowerCase()
        .replaceAll('_', '-')}`,
      title,
      status,
      detail,
      instance,
      code,
      traceId: MOCK_TRACE_ID,
    },
    {
      status,
      headers: {
        'Content-Type': 'application/problem+json',
      },
    },
  );
};

const validateCsrfToken = (request: Request, instance: string) => {
  if (request.headers.get('X-XSRF-TOKEN') === GUEST_TRIAL_MOCK_CSRF_TOKEN) {
    return null;
  }

  return createProblemDetails({
    status: 403,
    code: 'INVALID_CSRF_TOKEN',
    title: 'Invalid CSRF token',
    detail: 'CSRF Token이 유효하지 않습니다.',
    instance,
  });
};

const validateGuestSession = (state: GuestTrialMockState, instance: string) => {
  if (state.sessionIssued) {
    return null;
  }

  return createProblemDetails({
    status: 401,
    code: 'GUEST_SESSION_REQUIRED',
    title: 'Guest session required',
    detail: '게스트 세션이 필요합니다.',
    instance,
  });
};

const isGuestDiaryRequest = (value: unknown): value is GuestDiaryRequest => {
  if (!isRecord(value) || typeof value.sourceText !== 'string') {
    return false;
  }

  const sourceTextLength = Array.from(value.sourceText.trim()).length;

  return (
    typeof value.diaryDate === 'string' &&
    /^\d{4}-\d{2}-\d{2}$/.test(value.diaryDate) &&
    sourceTextLength >= 10 &&
    sourceTextLength <= 300
  );
};

const createRequestFingerprint = (request: GuestDiaryRequest): string => {
  return JSON.stringify([request.diaryDate, request.sourceText]);
};

const createMockUuid = (sequence: number): string => {
  return `00000000-0000-4000-8000-${String(sequence).padStart(12, '0')}`;
};

const createGuestDiaryResponse = (
  request: GuestDiaryRequest,
  sequence: number,
  job: MockGenerationJob,
): GuestDiaryResponse => {
  const diaryId = createMockUuid(sequence);

  return {
    id: diaryId,
    diaryDate: request.diaryDate,
    sourceText: request.sourceText,
    createdAt: new Date(job.acceptedAt).toISOString(),
    generation: {
      id: createMockUuid(sequence + 500),
      status: 'SUCCEEDED',
      title: '평범해서 더 기억하고 싶은 하루',
      imageUrl: diaryImage,
      imageUrlExpiresAt: new Date(job.acceptedAt + 3_600_000).toISOString(),
      completedAt: new Date(
        job.acceptedAt + MOCK_GENERATION_COMPLETION_MS,
      ).toISOString(),
    },
  };
};

const runGuestDiaryCreationExclusively = async <T>(
  operation: () => Promise<T>,
): Promise<T> => {
  const previousCreation = guestDiaryCreationQueue;
  let releaseCreation = (): void => undefined;

  guestDiaryCreationQueue = new Promise<void>((resolve) => {
    releaseCreation = resolve;
  });

  await previousCreation;

  try {
    return await operation();
  } finally {
    releaseCreation();
  }
};

export const guestTrialHandlers = [
  http.post('/api/v1/guest/session', async ({ request }) => {
    await delay(300);

    const csrfError = validateCsrfToken(request, '/api/v1/guest/session');

    if (csrfError) {
      return csrfError;
    }

    const state = loadState();
    saveState({ ...state, sessionIssued: true });

    return new HttpResponse(null, {
      status: 204,
      headers: {
        'Cache-Control': 'no-store',
      },
    });
  }),

  http.post('/api/v1/guest/diaries', async ({ request }) => {
    const instance = '/api/v1/guest/diaries';
    const csrfError = validateCsrfToken(request, instance);

    if (csrfError) {
      return csrfError;
    }

    const idempotencyKey = request.headers.get('Idempotency-Key');

    if (!isCanonicalUuid(idempotencyKey)) {
      return createProblemDetails({
        status: 400,
        code: 'INVALID_IDEMPOTENCY_KEY',
        title: 'Invalid idempotency key',
        detail: 'Idempotency-Key는 UUID 형식이어야 합니다.',
        instance,
      });
    }

    let requestBody: unknown;

    try {
      requestBody = await request.json();
    } catch {
      return createProblemDetails({
        status: 400,
        code: 'VALIDATION_ERROR',
        title: 'Validation failed',
        detail: '요청 본문이 올바른 JSON 형식이 아닙니다.',
        instance,
      });
    }

    if (!isGuestDiaryRequest(requestBody)) {
      return createProblemDetails({
        status: 400,
        code: 'VALIDATION_ERROR',
        title: 'Validation failed',
        detail: '일기 날짜와 내용이 올바르지 않습니다.',
        instance,
      });
    }

    const fingerprint = createRequestFingerprint(requestBody);

    return runGuestDiaryCreationExclusively(async () => {
      const state = loadState();
      const sessionError = validateGuestSession(state, instance);

      if (sessionError) {
        return sessionError;
      }

      const existingRequest = state.requests[idempotencyKey];

      if (existingRequest?.fingerprint === fingerprint) {
        if (
          getMockGeneration(
            existingRequest.job,
            existingRequest.response.generation,
          ).status === 'FAILED'
        ) {
          return createProblemDetails({
            status: 502,
            code: 'AI_PROVIDER_ERROR',
            title: 'AI provider error',
            detail: '이미지 생성 요청에 실패했습니다.',
            instance,
          });
        }
        await delay(150);
        return HttpResponse.json(
          {
            ...existingRequest.response,
            generation: getMockGeneration(
              existingRequest.job,
              existingRequest.response.generation,
            ),
          },
          {
            headers: {
              'Cache-Control': 'no-store',
            },
          },
        );
      }

      if (existingRequest) {
        return createProblemDetails({
          status: 409,
          code: 'IDEMPOTENCY_KEY_CONFLICT',
          title: 'Idempotency key conflict',
          detail: '같은 Idempotency-Key를 다른 요청에 사용할 수 없습니다.',
          instance,
        });
      }

      if (state.trialUsed) {
        return createProblemDetails({
          status: 409,
          code: 'GUEST_TRIAL_ALREADY_USED',
          title: 'Guest trial already used',
          detail: '게스트 체험을 이미 사용했습니다.',
          instance,
        });
      }

      await delay(300);

      const job = createMockGenerationJob(request);

      const response = createGuestDiaryResponse(
        requestBody,
        state.nextDiarySequence,
        job,
      );
      const nextState: GuestTrialMockState = {
        ...state,
        trialUsed: true,
        nextDiarySequence: state.nextDiarySequence + 1,
        requests: {
          ...state.requests,
          [idempotencyKey]: { fingerprint, response, job },
        },
        diaries: {
          ...state.diaries,
          [response.id]: response,
        },
      };

      saveState(nextState);

      return HttpResponse.json(
        {
          ...response,
          generation: getMockGeneration(job, response.generation),
        },
        {
          status: 202,
          headers: {
            'Cache-Control': 'no-store',
            Location: `/api/v1/guest/diaries/${response.id}`,
          },
        },
      );
    });
  }),

  http.get('/api/v1/guest/diaries/:diaryId', async ({ params }) => {
    await delay(250);

    const diaryId = String(params.diaryId);
    const instance = `/api/v1/guest/diaries/${diaryId}`;
    const state = loadState();
    const sessionError = validateGuestSession(state, instance);

    if (sessionError) {
      return sessionError;
    }

    const diary = state.diaries[diaryId];

    const record = Object.values(state.requests).find(
      ({ response }) => response.id === diaryId,
    );
    const generation =
      diary && record ? getMockGeneration(record.job, diary.generation) : null;

    if (!diary || !generation || generation.status === 'FAILED') {
      return createProblemDetails({
        status: 404,
        code: 'DIARY_NOT_FOUND',
        title: 'Diary not found',
        detail: '게스트 일기를 찾을 수 없습니다.',
        instance,
      });
    }

    return HttpResponse.json(
      { ...diary, generation },
      {
        headers: {
          'Cache-Control': 'no-store',
        },
      },
    );
  }),
  http.get(
    '/api/v1/guest/diary-generations/:generationId',
    async ({ params }) => {
      await delay(150);
      const generationId = String(params.generationId);
      const instance = `/api/v1/guest/diary-generations/${generationId}`;
      const state = loadState();
      const sessionError = validateGuestSession(state, instance);
      if (sessionError) return sessionError;

      if (!isCanonicalUuid(generationId)) {
        return createProblemDetails({
          status: 400,
          code: 'VALIDATION_ERROR',
          title: 'Validation failed',
          detail: '생성 ID는 UUID 형식이어야 합니다.',
          instance,
        });
      }

      const record = Object.values(state.requests).find(
        ({ response }) => response.generation.id === generationId,
      );
      if (!record) {
        return createProblemDetails({
          status: 404,
          code: 'GENERATION_NOT_FOUND',
          title: 'Generation not found',
          detail: '생성 작업을 찾을 수 없습니다.',
          instance,
        });
      }

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
];
