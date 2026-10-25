/** @jest-environment node */

import {
  afterEach,
  beforeEach,
  describe,
  expect,
  it,
  jest,
} from '@jest/globals';
import { handlers, resetDiaryGenerationMockState } from './handlers';
import {
  MOCK_GENERATION_PENDING_MS,
  MOCK_GENERATION_COMPLETION_MS,
} from './diaryGeneration';
import { MOCK_SCENARIO_HEADER, MOCK_SCENARIOS } from './mockScenarios';
import { isRecord } from '../shared/utils';

jest.mock('msw', () => {
  class MockHttpResponse extends Response {
    static json(data: unknown, init: ResponseInit = {}): MockHttpResponse {
      const headers = new Headers(init.headers);
      if (!headers.has('Content-Type'))
        headers.set('Content-Type', 'application/json');
      return new MockHttpResponse(JSON.stringify(data), { ...init, headers });
    }
  }
  const createHandler =
    (method: string) => (path: string, resolver: unknown) => ({
      method,
      path,
      resolver,
    });
  return {
    delay: (): Promise<void> => Promise.resolve(),
    http: {
      get: createHandler('GET'),
      post: createHandler('POST'),
      patch: createHandler('PATCH'),
      put: createHandler('PUT'),
      delete: createHandler('DELETE'),
    },
    HttpResponse: MockHttpResponse,
  };
});
jest.mock('../assets/images/diary-four-panel.webp', () => 'diary.webp');
jest.mock('../assets/images/profile-fallback.svg', () => 'profile.svg');
jest.mock('../assets/icons/article_person.svg', () => 'profile.svg');

interface MockHandler {
  method: string;
  path: string;
  resolver: (args: {
    request: Request;
    params: Record<string, string>;
  }) => Promise<Response>;
}

const memberHeaders = { Authorization: 'Bearer mock-access-token' };
const requestBody = {
  diaryDate: '2026-10-21',
  sourceText: '오늘 친구와 카페에서 오래 이야기했다.',
};
const firstKey = '7e5cc251-fdde-4cc0-a54e-2c8142750609';
const secondKey = '68fa9efb-fb12-45a7-b5cd-66387d3f60a7';
const thirdKey = '68fa9efb-fb12-45a7-b5cd-66387d3f60a8';
const fourthKey = '68fa9efb-fb12-45a7-b5cd-66387d3f60a9';

const requestApi = async (
  path: string,
  init: RequestInit = {},
): Promise<Response> => {
  const request = new Request(`http://localhost${path}`, {
    ...init,
    headers: init.headers ?? memberHeaders,
  });
  // jest.mock에서 만든 핸들러 형태를 테스트에서만 사용한다.
  for (const handler of handlers as unknown as MockHandler[]) {
    if (handler.method !== request.method) continue;
    const names = Array.from(
      handler.path.matchAll(/:([^/]+)/g),
      (match): string => match[1],
    );
    const match = new URL(request.url).pathname.match(
      new RegExp(`^${handler.path.replace(/:[^/]+/g, '([^/]+)')}$`),
    );
    if (!match) continue;
    return handler.resolver({
      request,
      params: Object.fromEntries(
        names.map((name, index) => [name, match[index + 1]]),
      ),
    });
  }
  throw new Error(`MSW 핸들러가 없습니다: ${path}`);
};

const createDiary = (
  key = firstKey,
  scenario?: string,
  body = requestBody,
): Promise<Response> =>
  requestApi('/api/v1/diaries', {
    method: 'POST',
    headers: {
      ...memberHeaders,
      'Content-Type': 'application/json',
      'Idempotency-Key': key,
      ...(scenario ? { [MOCK_SCENARIO_HEADER]: scenario } : {}),
    },
    body: JSON.stringify(body),
  });

const readIds = async (
  response: Response,
): Promise<{ id: string; generationId: string }> => {
  const data: unknown = await response.json();
  if (
    !isRecord(data) ||
    typeof data.id !== 'string' ||
    !isRecord(data.generation) ||
    typeof data.generation.id !== 'string'
  )
    throw new Error('일기 ID와 생성 ID가 필요합니다.');
  return { id: data.id, generationId: data.generation.id };
};
const getGeneration = (id: string): Promise<Response> =>
  requestApi(`/api/v1/diary-generations/${id}`);

beforeEach((): void => {
  jest.spyOn(Date, 'now').mockReturnValue(1_790_000_000_000);
  resetDiaryGenerationMockState();
});
afterEach((): void => {
  jest.restoreAllMocks();
});

describe('회원 비동기 일기 생성 MSW 계약', (): void => {
  it('202로 접수하고 시간에 따라 대기 → 처리 중 → 성공 및 상세·목록을 반환한다', async (): Promise<void> => {
    const response = await createDiary();
    expect(response.status).toBe(202);
    const accepted: unknown = await response.clone().json();
    expect(accepted).toMatchObject({
      generation: {
        status: 'PENDING',
        title: null,
        imageUrl: null,
        imageUrlExpiresAt: null,
        completedAt: null,
      },
      usage: { usedCount: 1, remainingCount: 2 },
    });
    const { id, generationId } = await readIds(response);
    expect(response.headers.get('Location')).toBe(`/api/v1/diaries/${id}`);
    const start = Date.now();
    for (let i = 0; i < 2; i += 1) {
      const status = await getGeneration(generationId);
      expect(status.status).toBe(200);
      expect(status.headers.get('Cache-Control')).toBe('no-store');
      await expect(status.json()).resolves.toEqual({
        id: generationId,
        status: 'PENDING',
        diaryId: null,
        errorCode: null,
      });
    }
    jest.spyOn(Date, 'now').mockReturnValue(start + MOCK_GENERATION_PENDING_MS);
    await expect(
      (await getGeneration(generationId)).json(),
    ).resolves.toMatchObject({
      status: 'PROCESSING',
      diaryId: null,
      errorCode: null,
    });
    const replay = await createDiary();
    expect(replay.status).toBe(200);
    await expect(replay.json()).resolves.toMatchObject({
      id,
      generation: { id: generationId, status: 'PROCESSING', imageUrl: null },
    });
    jest
      .spyOn(Date, 'now')
      .mockReturnValue(start + MOCK_GENERATION_COMPLETION_MS);
    // 상태 조회 없이도 완료된 일기를 상세·목록에서 볼 수 있어야 한다.
    await expect(
      (await requestApi(`/api/v1/diaries/${id}`)).json(),
    ).resolves.toMatchObject({
      id,
      generation: { status: 'SUCCEEDED', imageUrl: 'diary.webp' },
    });
    await expect((await getGeneration(generationId)).json()).resolves.toEqual({
      id: generationId,
      status: 'SUCCEEDED',
      diaryId: id,
      errorCode: null,
    });
    const monthly = await requestApi('/api/v1/diaries?year=2026&month=10');
    await expect(monthly.json()).resolves.toMatchObject({
      days: expect.arrayContaining([
        expect.objectContaining({
          date: requestBody.diaryDate,
          items: expect.arrayContaining([expect.objectContaining({ id })]),
        }),
      ]),
    });
    await expect((await createDiary()).json()).resolves.toMatchObject({
      id,
      generation: { status: 'SUCCEEDED' },
    });
    await expect(
      (await requestApi('/api/v1/me/generation-usage')).json(),
    ).resolves.toMatchObject({ usedCount: 1 });
  });

  it('실패 조회는 200과 오류 코드를 반환하고 일기를 숨기며 사용량은 한 번만 복구한다', async (): Promise<void> => {
    const { id, generationId } = await readIds(
      await createDiary(firstKey, MOCK_SCENARIOS.diaryGenerationFailure),
    );
    jest
      .spyOn(Date, 'now')
      .mockReturnValue(Date.now() + MOCK_GENERATION_COMPLETION_MS);
    for (let i = 0; i < 2; i += 1) {
      const status = await getGeneration(generationId);
      expect(status.status).toBe(200);
      await expect(status.json()).resolves.toEqual({
        id: generationId,
        status: 'FAILED',
        diaryId: null,
        errorCode: 'AI_PROVIDER_ERROR',
      });
    }
    expect((await requestApi(`/api/v1/diaries/${id}`)).status).toBe(404);
    await expect(
      (await requestApi('/api/v1/me/generation-usage')).json(),
    ).resolves.toMatchObject({ usedCount: 0, remainingCount: 3 });
    const replay = await createDiary();
    expect(replay.status).toBe(502);
    await expect(replay.json()).resolves.toMatchObject({
      code: 'AI_PROVIDER_ERROR',
    });
    expect((await createDiary(secondKey)).status).toBe(202);
  });

  it('동시 재요청은 한 번만 접수하고 다른 본문은 충돌하며 3회 제한을 지킨다', async (): Promise<void> => {
    const responses = await Promise.all([createDiary(), createDiary()]);
    expect(responses.map(({ status }) => status).sort()).toEqual([200, 202]);
    expect(await readIds(responses[0])).toEqual(await readIds(responses[1]));
    expect(
      (
        await createDiary(firstKey, undefined, {
          ...requestBody,
          sourceText: '다른 내용',
        })
      ).status,
    ).toBe(409);
    expect((await createDiary(secondKey)).status).toBe(202);
    expect((await createDiary(thirdKey)).status).toBe(202);
    expect((await createDiary(fourthKey)).status).toBe(429);
    expect((await createDiary()).status).toBe(200);
  });

  it.each([0, MOCK_GENERATION_PENDING_MS, MOCK_GENERATION_COMPLETION_MS])(
    '접수 후 %dms에 삭제한 일기는 상태 조회와 재접수에서 404를 반환한다',
    async (elapsed): Promise<void> => {
      const { id, generationId } = await readIds(await createDiary());
      jest.spyOn(Date, 'now').mockReturnValue(Date.now() + elapsed);
      expect(
        (await requestApi(`/api/v1/diaries/${id}`, { method: 'DELETE' }))
          .status,
      ).toBe(204);
      expect((await getGeneration(generationId)).status).toBe(404);
      expect((await createDiary()).status).toBe(404);
      expect((await requestApi(`/api/v1/diaries/${id}`)).status).toBe(404);
    },
  );

  it('인증 없음과 잘못된 ID 및 존재하지 않는 작업을 거절한다', async (): Promise<void> => {
    expect(
      (
        await requestApi(`/api/v1/diary-generations/${firstKey}`, {
          headers: {},
        })
      ).status,
    ).toBe(401);
    expect((await getGeneration('invalid')).status).toBe(400);
    const missing = await getGeneration(firstKey);
    expect(missing.status).toBe(404);
    expect(missing.headers.get('Content-Type')).toBe(
      'application/problem+json',
    );
    await expect(missing.json()).resolves.toMatchObject({
      code: 'GENERATION_NOT_FOUND',
    });
  });
});
