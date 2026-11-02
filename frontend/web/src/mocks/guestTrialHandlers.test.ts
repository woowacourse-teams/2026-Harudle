/** @jest-environment node */

import {
  afterAll,
  afterEach,
  beforeAll,
  beforeEach,
  describe,
  expect,
  it,
  jest,
} from '@jest/globals';
import {
  GUEST_TRIAL_MOCK_CSRF_TOKEN,
  guestTrialHandlers,
  resetGuestTrialMockState,
} from './guestTrialHandlers';
import { isGuestDiaryResponse } from '../pages/landing/guestTrialApi';
import { isRecord } from '../shared/utils';
import {
  MOCK_GENERATION_PENDING_MS,
  MOCK_GENERATION_COMPLETION_MS,
} from './diaryGeneration';
import { MOCK_SCENARIO_HEADER, MOCK_SCENARIOS } from './mockScenarios';

jest.mock('msw', () => {
  class MockHttpResponse extends Response {
    static json(data: unknown, init: ResponseInit = {}): MockHttpResponse {
      const headers = new Headers(init.headers);
      headers.set('Content-Type', 'application/json');

      return new MockHttpResponse(JSON.stringify(data), {
        ...init,
        headers,
      });
    }
  }

  const createHandler = (method: string) => {
    return (path: string, resolver: unknown) => ({ method, path, resolver });
  };

  return {
    delay: () => Promise.resolve(),
    http: {
      get: createHandler('GET'),
      post: createHandler('POST'),
    },
    HttpResponse: MockHttpResponse,
  };
});

jest.mock('../assets/images/diary-four-panel.webp', () => 'guest-diary.webp');

const API_ORIGIN = 'http://localhost';
const DIARY_REQUEST = {
  diaryDate: '2026-08-21',
  sourceText: '퇴근길에 비를 맞았지만 친구와 웃었던 하루였다.',
};
const FIRST_IDEMPOTENCY_KEY = '7e5cc251-fdde-4cc0-a54e-2c8142750609';
const SECOND_IDEMPOTENCY_KEY = '68fa9efb-fb12-45a7-b5cd-66387d3f60a7';
const GUEST_TRIAL_STATE_STORAGE_KEY = 'harudle:mock:guest-trial:v2';

class MemoryStorage implements Storage {
  private readonly values = new Map<string, string>();

  get length(): number {
    return this.values.size;
  }

  clear(): void {
    this.values.clear();
  }

  getItem(key: string): string | null {
    return this.values.get(key) ?? null;
  }

  key(index: number): string | null {
    return Array.from(this.values.keys())[index] ?? null;
  }

  removeItem(key: string): void {
    this.values.delete(key);
  }

  setItem(key: string, value: string): void {
    this.values.set(key, value);
  }
}

const memoryStorage = new MemoryStorage();
const originalSessionStorage = Object.getOwnPropertyDescriptor(
  globalThis,
  'sessionStorage',
);

interface MockHandler {
  method: string;
  path: string;
  resolver: (args: {
    request: Request;
    params: Record<string, string>;
  }) => Response | Promise<Response>;
}

const resolveGuestTrialRequest = async (request: Request) => {
  const requestPath = new URL(request.url).pathname;
  const handlers = guestTrialHandlers as unknown as MockHandler[];

  for (const handler of handlers) {
    if (handler.method !== request.method) {
      continue;
    }

    const parameterNames = Array.from(
      handler.path.matchAll(/:([^/]+)/g),
      (match): string => match[1],
    );
    const pattern = new RegExp(
      `^${handler.path.replace(/:[^/]+/g, '([^/]+)')}$`,
    );
    const match = requestPath.match(pattern);
    if (!match) continue;
    return handler.resolver({
      request,
      params: Object.fromEntries(
        parameterNames.map((name, index) => [name, match[index + 1]]),
      ),
    });
  }

  throw new Error(`처리할 게스트 MSW 핸들러가 없습니다: ${request.url}`);
};

const issueGuestSession = (csrfToken = GUEST_TRIAL_MOCK_CSRF_TOKEN) => {
  return resolveGuestTrialRequest(
    new Request(`${API_ORIGIN}/api/v1/guest/session`, {
      method: 'POST',
      credentials: 'include',
      headers: {
        'X-XSRF-TOKEN': csrfToken,
      },
    }),
  );
};

const createGuestDiary = ({
  idempotencyKey = FIRST_IDEMPOTENCY_KEY,
  csrfToken = GUEST_TRIAL_MOCK_CSRF_TOKEN,
  request = DIARY_REQUEST,
  scenario,
}: {
  idempotencyKey?: string;
  csrfToken?: string;
  request?: typeof DIARY_REQUEST;
  scenario?: string;
} = {}) => {
  return resolveGuestTrialRequest(
    new Request(`${API_ORIGIN}/api/v1/guest/diaries`, {
      method: 'POST',
      credentials: 'include',
      headers: {
        'Content-Type': 'application/json',
        'Idempotency-Key': idempotencyKey,
        'X-XSRF-TOKEN': csrfToken,
        ...(scenario ? { [MOCK_SCENARIO_HEADER]: scenario } : {}),
      },
      body: JSON.stringify(request),
    }),
  );
};

const readGuestDiaryResponse = async (
  response: Response,
): Promise<{ id: string; generation: { id: string } }> => {
  const data: unknown = await response.json();

  if (
    !isRecord(data) ||
    typeof data.id !== 'string' ||
    !isRecord(data.generation) ||
    typeof data.generation.id !== 'string'
  ) {
    throw new Error('게스트 일기 응답 형식이 올바르지 않습니다.');
  }

  return { id: data.id, generation: { id: data.generation.id } };
};

const getGeneration = (id: string): Promise<Response> =>
  resolveGuestTrialRequest(
    new Request(`${API_ORIGIN}/api/v1/guest/diary-generations/${id}`),
  );

beforeAll(() => {
  Object.defineProperty(globalThis, 'sessionStorage', {
    configurable: true,
    value: memoryStorage,
  });
});

beforeEach(() => {
  jest.spyOn(Date, 'now').mockReturnValue(1_790_000_000_000);
  memoryStorage.clear();
  resetGuestTrialMockState();
});

afterEach((): void => {
  jest.restoreAllMocks();
});

afterAll(() => {
  if (originalSessionStorage) {
    Object.defineProperty(globalThis, 'sessionStorage', originalSessionStorage);
    return;
  }

  Reflect.deleteProperty(globalThis, 'sessionStorage');
});

describe('게스트 체험 MSW 시나리오', () => {
  it('최초 사용 시 세션을 발급하고 일기를 생성한 뒤 결과를 조회한다', async () => {
    const sessionResponse = await issueGuestSession();
    const createResponse = await createGuestDiary();
    const createdDiary = await readGuestDiaryResponse(createResponse);

    expect(sessionResponse.status).toBe(204);
    expect(createResponse.status).toBe(202);
    expect(createResponse.headers.get('Location')).toBe(
      `/api/v1/guest/diaries/${createdDiary.id}`,
    );
    expect(memoryStorage.getItem(GUEST_TRIAL_STATE_STORAGE_KEY)).not.toBeNull();

    const resultResponse = await resolveGuestTrialRequest(
      new Request(`${API_ORIGIN}/api/v1/guest/diaries/${createdDiary.id}`, {
        credentials: 'include',
      }),
    );

    await expect(resultResponse.json()).resolves.toMatchObject({
      ...createdDiary,
      generation: {
        ...createdDiary.generation,
        status: 'PENDING',
        title: null,
        imageUrl: null,
      },
    });
    expect(resultResponse.status).toBe(200);
  });

  it('새로고침 이후에도 sessionStorage에 저장된 결과를 조회한다', async () => {
    await issueGuestSession();
    const createResponse = await createGuestDiary();
    const createdDiary = await readGuestDiaryResponse(createResponse);
    const storedState: unknown = JSON.parse(
      memoryStorage.getItem(GUEST_TRIAL_STATE_STORAGE_KEY) ?? '{}',
    );

    if (!isRecord(storedState) || !isRecord(storedState.diaries)) {
      throw new Error('저장된 게스트 체험 상태가 올바르지 않습니다.');
    }

    const storedDiary = storedState.diaries[createdDiary.id];
    if (!isGuestDiaryResponse(storedDiary)) {
      throw new Error('저장된 게스트 일기 형식이 올바르지 않습니다.');
    }

    storedDiary.generation.title = '세션 저장소에서 복원한 결과';
    jest
      .spyOn(Date, 'now')
      .mockReturnValue(Date.now() + MOCK_GENERATION_COMPLETION_MS);
    memoryStorage.setItem(
      GUEST_TRIAL_STATE_STORAGE_KEY,
      JSON.stringify(storedState),
    );

    const resultResponse = await resolveGuestTrialRequest(
      new Request(`${API_ORIGIN}/api/v1/guest/diaries/${createdDiary.id}`),
    );

    await expect(resultResponse.json()).resolves.toMatchObject({
      generation: {
        title: '세션 저장소에서 복원한 결과',
      },
    });
  });

  it('같은 멱등 키와 요청을 다시 보내면 같은 결과를 200으로 반환한다', async () => {
    await issueGuestSession();

    const firstResponse = await createGuestDiary();
    const firstDiary: unknown = await firstResponse.json();
    const replayResponse = await createGuestDiary();

    expect(firstResponse.status).toBe(202);
    expect(replayResponse.status).toBe(200);
    await expect(replayResponse.json()).resolves.toEqual(firstDiary);
  });

  it('같은 멱등 키의 동시 요청은 하나만 생성하고 나머지는 재응답한다', async () => {
    await issueGuestSession();

    const responses = await Promise.all([
      createGuestDiary(),
      createGuestDiary(),
    ]);
    const responseBodies = await Promise.all(
      responses.map((response) => response.json()),
    );

    expect(responses.map(({ status }) => status).sort((a, b) => a - b)).toEqual(
      [200, 202],
    );
    expect(responseBodies[0]).toEqual(responseBodies[1]);
  });

  it('서로 다른 멱등 키의 동시 요청은 하나만 생성한다', async () => {
    await issueGuestSession();

    const responses = await Promise.all([
      createGuestDiary(),
      createGuestDiary({
        idempotencyKey: SECOND_IDEMPOTENCY_KEY,
      }),
    ]);
    const rejectedResponse = responses.find(({ status }) => status === 409);

    expect(responses.map(({ status }) => status).sort((a, b) => a - b)).toEqual(
      [202, 409],
    );
    expect(rejectedResponse).toBeDefined();
    await expect(rejectedResponse?.json()).resolves.toMatchObject({
      code: 'GUEST_TRIAL_ALREADY_USED',
    });
  });

  it('체험을 마친 뒤 새 멱등 키로 생성하면 이미 사용한 오류를 반환한다', async () => {
    await issueGuestSession();
    await createGuestDiary();

    const response = await createGuestDiary({
      idempotencyKey: SECOND_IDEMPOTENCY_KEY,
    });

    expect(response.status).toBe(409);
    await expect(response.json()).resolves.toMatchObject({
      code: 'GUEST_TRIAL_ALREADY_USED',
      instance: '/api/v1/guest/diaries',
    });
  });

  it('공백을 제외한 일기 내용이 10자 미만이면 생성 요청을 거부한다', async () => {
    await issueGuestSession();

    const response = await createGuestDiary({
      request: {
        ...DIARY_REQUEST,
        sourceText: '  짧은 일기  ',
      },
    });

    expect(response.status).toBe(400);
    await expect(response.json()).resolves.toMatchObject({
      code: 'VALIDATION_ERROR',
    });
  });

  it('유효하지 않은 CSRF Token이면 세션과 생성 요청을 거부한다', async () => {
    const sessionResponse = await issueGuestSession('invalid-token');
    const createResponse = await createGuestDiary({
      csrfToken: 'invalid-token',
    });

    expect(sessionResponse.status).toBe(403);
    await expect(sessionResponse.json()).resolves.toMatchObject({
      code: 'INVALID_CSRF_TOKEN',
    });
    expect(createResponse.status).toBe(403);
    await expect(createResponse.json()).resolves.toMatchObject({
      code: 'INVALID_CSRF_TOKEN',
    });
  });
  it('반복 조회는 상태를 진행시키지 않고 시간이 지나면 처리 중과 성공 결과를 반환한다', async (): Promise<void> => {
    await issueGuestSession();
    const accepted = await readGuestDiaryResponse(await createGuestDiary());
    const start = Date.now();
    for (let i = 0; i < 2; i += 1) {
      const response = await getGeneration(accepted.generation.id);
      expect(response.status).toBe(200);
      expect(response.headers.get('Cache-Control')).toBe('no-store');
      await expect(response.json()).resolves.toEqual({
        id: accepted.generation.id,
        status: 'PENDING',
        diaryId: null,
        errorCode: null,
      });
    }
    jest.spyOn(Date, 'now').mockReturnValue(start + MOCK_GENERATION_PENDING_MS);
    await expect(
      (await getGeneration(accepted.generation.id)).json(),
    ).resolves.toMatchObject({
      status: 'PROCESSING',
      diaryId: null,
      errorCode: null,
    });
    await expect((await createGuestDiary()).json()).resolves.toMatchObject({
      generation: { id: accepted.generation.id, status: 'PROCESSING' },
    });
    jest
      .spyOn(Date, 'now')
      .mockReturnValue(start + MOCK_GENERATION_COMPLETION_MS);
    await expect(
      (await getGeneration(accepted.generation.id)).json(),
    ).resolves.toEqual({
      id: accepted.generation.id,
      status: 'SUCCEEDED',
      diaryId: accepted.id,
      errorCode: null,
    });
    const detail = await resolveGuestTrialRequest(
      new Request(`${API_ORIGIN}/api/v1/guest/diaries/${accepted.id}`),
    );
    expect(isGuestDiaryResponse(await detail.json())).toBe(true);
    expect(
      (await createGuestDiary({ idempotencyKey: SECOND_IDEMPOTENCY_KEY }))
        .status,
    ).toBe(409);
  });

  it('실패해도 상태 조회는 200이며 실패한 일기는 숨기고 체험권을 다시 주지 않는다', async (): Promise<void> => {
    await issueGuestSession();
    const accepted = await readGuestDiaryResponse(
      await createGuestDiary({
        scenario: MOCK_SCENARIOS.diaryGenerationFailure,
      }),
    );
    jest
      .spyOn(Date, 'now')
      .mockReturnValue(Date.now() + MOCK_GENERATION_COMPLETION_MS);
    const failed = await getGeneration(accepted.generation.id);
    expect(failed.status).toBe(200);
    await expect(failed.json()).resolves.toEqual({
      id: accepted.generation.id,
      status: 'FAILED',
      diaryId: null,
      errorCode: 'AI_PROVIDER_ERROR',
    });
    expect((await createGuestDiary()).status).toBe(502);
    expect(
      (await createGuestDiary({ idempotencyKey: SECOND_IDEMPOTENCY_KEY }))
        .status,
    ).toBe(409);
    const detail = await resolveGuestTrialRequest(
      new Request(`${API_ORIGIN}/api/v1/guest/diaries/${accepted.id}`),
    );
    expect(detail.status).toBe(404);
  });

  it('세션 없음, 잘못된 ID, 다른 세션 또는 존재하지 않는 작업을 구분한다', async (): Promise<void> => {
    expect((await getGeneration(FIRST_IDEMPOTENCY_KEY)).status).toBe(401);
    await issueGuestSession();
    expect((await getGeneration('invalid')).status).toBe(400);
    await expect(
      (await getGeneration(FIRST_IDEMPOTENCY_KEY)).json(),
    ).resolves.toMatchObject({ code: 'GENERATION_NOT_FOUND' });
    const accepted = await readGuestDiaryResponse(await createGuestDiary());
    resetGuestTrialMockState();
    await issueGuestSession();
    expect((await getGeneration(accepted.generation.id)).status).toBe(404);
  });
});
