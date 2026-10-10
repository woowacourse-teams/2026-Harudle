import { delay, http, HttpResponse } from 'msw';

interface MockCategory {
  readonly id: string;
  readonly name: string;
  readonly sortOrder: number;
  readonly active: boolean;
}

const MOCK_ACCESS_TOKEN = 'mock-access-token';

// 카테고리 개별 명세의 auto increment ID를 문자열로 반환한다.
const categories: readonly MockCategory[] = [
  { id: '1', name: '일상', sortOrder: 0, active: true },
  { id: '2', name: '우테코', sortOrder: 1, active: true },
];

const problem = (
  request: Request,
  status: number,
  code: 'UNAUTHORIZED',
): Response => {
  return HttpResponse.json(
    {
      type: `urn:harudle:problem:${code.toLowerCase().replaceAll('_', '-')}`,
      title: code,
      status,
      detail: code,
      instance: new URL(request.url).pathname,
      code,
      traceId: 'mock-feed-trace-id',
    },
    { status, headers: { 'Content-Type': 'application/problem+json' } },
  );
};

const isAuthenticated = (request: Request): boolean =>
  request.headers.get('Authorization') === `Bearer ${MOCK_ACCESS_TOKEN}`;

const validateAuthentication = (request: Request): Response | null => {
  if (request.headers.has('Authorization') && !isAuthenticated(request)) {
    const response = problem(request, 401, 'UNAUTHORIZED');
    response.headers.set('WWW-Authenticate', 'Bearer');
    return response;
  }

  return null;
};

export const feedHandlers = [
  http.get('/api/v1/categories', async ({ request }): Promise<Response> => {
    await delay(300);
    const error = validateAuthentication(request);
    return error ?? HttpResponse.json(categories);
  }),
];
