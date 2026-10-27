import { delay, http, HttpResponse } from 'msw';
import { isCanonicalUuid } from '../pages/landing/guestTrialUuid';
import { readMockBlocks, writeMockBlocks } from './blockMockState';
import { getMockFeedAuthor } from './feedHandlers';
import { MOCK_SCENARIO_HEADER, MOCK_SCENARIOS } from './mockScenarios';

const failure = (request: Request, status: number, code: string): Response =>
  HttpResponse.json(
    {
      type: `urn:harudle:problem:${code}`,
      title: code,
      status,
      detail: '요청을 처리하지 못했어요. 다시 시도해주세요.',
      code,
      instance: new URL(request.url).pathname,
    },
    { status, headers: { 'Content-Type': 'application/problem+json' } },
  );
const validate = (request: Request, mutation = false): Response | null => {
  if (request.headers.get('Authorization') !== 'Bearer mock-access-token')
    return failure(request, 401, 'UNAUTHORIZED');
  if (mutation && request.headers.get('X-XSRF-TOKEN') !== 'mock-csrf-token')
    return failure(request, 403, 'INVALID_CSRF_TOKEN');
  return null;
};
export const blockHandlers = [
  http.get('/api/v1/blocks', async ({ request }): Promise<Response> => {
    await delay(150);
    const error = validate(request);
    if (error) return error;
    if (
      request.headers.get(MOCK_SCENARIO_HEADER) ===
      MOCK_SCENARIOS.blockListFailure
    )
      return failure(request, 503, 'BLOCK_UNAVAILABLE');
    const url = new URL(request.url);
    const size = Number(url.searchParams.get('size') ?? '20');
    const cursor = url.searchParams.get('cursor');
    const items = readMockBlocks();
    const index =
      cursor === null
        ? 0
        : items.findIndex((item): boolean => item.userId === cursor) + 1;
    if (
      !Number.isInteger(size) ||
      size < 1 ||
      size > 50 ||
      (cursor !== null && index === 0)
    )
      return failure(request, 400, 'INVALID_CURSOR');
    const page = items.slice(index, index + size);
    const hasNext = items.length > index + size;
    return HttpResponse.json({
      items: page,
      hasNext,
      nextCursor: hasNext ? page.at(-1)?.userId : null,
    });
  }),
  http.put(
    '/api/v1/blocks/:userId',
    async ({ request, params }): Promise<Response> => {
      await delay(300);
      const error = validate(request, true);
      if (error) return error;
      const userId = String(params.userId);
      if (
        !isCanonicalUuid(userId) ||
        userId === '08d69a34-6d70-4d42-a158-671bc67733c9'
      )
        return failure(request, 400, 'INVALID_BLOCK');
      const author = getMockFeedAuthor(userId);
      if (!author) return failure(request, 404, 'BLOCK_TARGET_NOT_FOUND');
      if (
        request.headers.get(MOCK_SCENARIO_HEADER) ===
        MOCK_SCENARIOS.blockMutationFailure
      )
        return failure(request, 503, 'BLOCK_UNAVAILABLE');
      const items = readMockBlocks();
      if (!items.some((item): boolean => item.userId === userId))
        writeMockBlocks([
          {
            userId,
            nickname: author.nickname,
            profileImageUrl: author.profileImageUrl,
            blockedAt: new Date().toISOString(),
          },
          ...items,
        ]);
      return new HttpResponse(null, { status: 204 });
    },
  ),
  http.delete(
    '/api/v1/blocks/:userId',
    async ({ request, params }): Promise<Response> => {
      await delay(150);
      const error = validate(request, true);
      if (error) return error;
      if (
        request.headers.get(MOCK_SCENARIO_HEADER) ===
        MOCK_SCENARIOS.blockMutationFailure
      )
        return failure(request, 503, 'BLOCK_UNAVAILABLE');
      writeMockBlocks(
        readMockBlocks().filter(
          (item): boolean => item.userId !== params.userId,
        ),
      );
      return new HttpResponse(null, { status: 204 });
    },
  ),
];
