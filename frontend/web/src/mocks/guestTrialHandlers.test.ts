/** @jest-environment node */

import {
  afterAll,
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

jest.mock('../assets/images/diary-four-panel.png', () => 'guest-diary.png');

const API_ORIGIN = 'http://localhost';
const DIARY_REQUEST = {
  diaryDate: '2026-08-21',
  sourceText: '퇴근길에 비를 맞았지만 친구와 웃었던 하루였다.',
};
const FIRST_IDEMPOTENCY_KEY = '7e5cc251-fdde-4cc0-a54e-2c8142750609';
const SECOND_IDEMPOTENCY_KEY = '68fa9efb-fb12-45a7-b5cd-66387d3f60a7';
const GUEST_TRIAL_STATE_STORAGE_KEY = 'harudle:mock:guest-trial';

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

    const diaryPathMatch = requestPath.match(
      /^\/api\/v1\/guest\/diaries\/([^/]+)$/,
    );
    const isExactMatch = handler.path === requestPath;
    const isDiaryResultMatch =
      handler.path === '/api/v1/guest/diaries/:diaryId' && diaryPathMatch;

    if (!isExactMatch && !isDiaryResultMatch) {
      continue;
    }

    return handler.resolver({
      request,
      params: diaryPathMatch ? { diaryId: diaryPathMatch[1] } : {},
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
}: {
  idempotencyKey?: string;
  csrfToken?: string;
  request?: typeof DIARY_REQUEST;
} = {}) => {
  return resolveGuestTrialRequest(
    new Request(`${API_ORIGIN}/api/v1/guest/diaries`, {
      method: 'POST',
      credentials: 'include',
      headers: {
        'Content-Type': 'application/json',
        'Idempotency-Key': idempotencyKey,
        'X-XSRF-TOKEN': csrfToken,
      },
      body: JSON.stringify(request),
    }),
  );
};

beforeAll(() => {
  Object.defineProperty(globalThis, 'sessionStorage', {
    configurable: true,
    value: memoryStorage,
  });
});

beforeEach(() => {
  memoryStorage.clear();
  resetGuestTrialMockState();
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
    const createdDiary = await createResponse.json();

    expect(sessionResponse.status).toBe(204);
    expect(createResponse.status).toBe(201);
    expect(createResponse.headers.get('Location')).toBe(
      `/api/v1/guest/diaries/${createdDiary.id}`,
    );
    expect(memoryStorage.getItem(GUEST_TRIAL_STATE_STORAGE_KEY)).not.toBeNull();

    const resultResponse = await resolveGuestTrialRequest(
      new Request(`${API_ORIGIN}/api/v1/guest/diaries/${createdDiary.id}`, {
        credentials: 'include',
      }),
    );

    await expect(resultResponse.json()).resolves.toEqual(createdDiary);
    expect(resultResponse.status).toBe(200);
  });

  it('새로고침 이후에도 sessionStorage에 저장된 결과를 조회한다', async () => {
    await issueGuestSession();
    const createResponse = await createGuestDiary();
    const createdDiary = await createResponse.json();
    const storedState = JSON.parse(
      memoryStorage.getItem(GUEST_TRIAL_STATE_STORAGE_KEY) ?? '{}',
    );

    storedState.diaries[createdDiary.id].generation.title =
      '세션 저장소에서 복원한 결과';
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
    const firstDiary = await firstResponse.json();
    const replayResponse = await createGuestDiary();

    expect(firstResponse.status).toBe(201);
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

    expect(responses.map(({ status }) => status).sort()).toEqual([200, 201]);
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

    expect(responses.map(({ status }) => status).sort()).toEqual([201, 409]);
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
});
