import {
  afterEach,
  beforeEach,
  describe,
  expect,
  it,
  jest,
} from '@jest/globals';
import { RequestError } from '../../../shared/api';
import { setAccessToken } from '../../../shared/auth';
import { resolveGuestTrialClaim, submitGuestTrialDiary } from './guestTrialApi';

const DIARY_ID = '0b9c1c5e-6f0a-4f0e-9a51-6c1c3b2f7d10';
const GENERATION_ID = '3b85d827-72da-4e23-98ef-a97119e441b8';

const memberDiary = {
  id: DIARY_ID,
  publishedFeedId: null,
  diaryDate: '2026-11-06',
  sourceText: '친구가 민트초코에 치즈폼을 올려 먹어보라고 했다.',
  createdAt: '2026-11-06T10:00:00Z',
  generation: {
    id: GENERATION_ID,
    status: 'SUCCEEDED',
    title: '나의 최애 음료',
    imageUrl: 'https://example.com/diary.webp',
    imageUrlExpiresAt: '2026-11-06T10:15:00Z',
    completedAt: '2026-11-06T10:01:00Z',
  },
} as const;

const problem = (status: number, code: string) => ({
  type: `https://api.harudle.example/problems/${code}`,
  title: code,
  status,
  detail: `${code} 상세`,
  instance: '/api/v1/guest/diary-claim',
  code,
});

const jsonResponse = (data: unknown, status = 200): Response => {
  const response = {
    ok: status >= 200 && status < 300,
    status,
    json: () => Promise.resolve(data),
    clone: () => jsonResponse(data, status),
  };
  // jsdom에는 Response가 없어 테스트에 필요한 속성만 갖춘 응답을 사용한다.
  return response as unknown as Response;
};

type Route = () => Promise<Response>;

const fetchMock = jest.fn<typeof fetch>();
const originalFetch = globalThis.fetch;

/** 요청 경로별 응답을 차례대로 돌려준다. 준비되지 않은 요청은 실패시킨다. */
const routeFetch = (routes: Record<string, Route[]>): void => {
  globalThis.fetch = fetchMock;
  fetchMock.mockImplementation(async (info, init): Promise<Response> => {
    const key = `${init?.method ?? 'GET'} ${String(info)}`;
    const route = routes[key]?.shift();
    if (!route) throw new Error(`준비되지 않은 요청: ${key}`);
    return route();
  });
};

const csrf: Route = async () => jsonResponse({ token: 'csrf-token' });
const networkError: Route = async () => {
  throw new TypeError('Failed to fetch');
};
const CLAIM = 'POST /api/v1/guest/diary-claim';
const CSRF = 'GET /api/v1/auth/csrf';
const MEMBER_DIARY = `GET /api/v1/diaries/${DIARY_ID}`;

beforeEach((): void => {
  localStorage.setItem('harudle.has-completed-oauth', 'true');
  setAccessToken('member-token');
});

afterEach((): void => {
  globalThis.fetch = originalFetch;
  localStorage.clear();
  setAccessToken(null);
});

describe('체험 일기 claim 결과 판단', (): void => {
  it('claim이 성공하면 회원 상세로 소유를 확인한 결과를 돌려준다', async (): Promise<void> => {
    routeFetch({
      [CSRF]: [csrf],
      [CLAIM]: [
        async () =>
          jsonResponse({
            diaryId: DIARY_ID,
            diaryDate: '2026-11-06',
            generationStatus: 'SUCCEEDED',
            claimedAt: '2026-11-06T10:05:00Z',
          }),
      ],
      [MEMBER_DIARY]: [async () => jsonResponse(memberDiary)],
    });

    await expect(resolveGuestTrialClaim(DIARY_ID)).resolves.toEqual({
      type: 'claimed',
      diary: memberDiary,
    });

    const claimInit = fetchMock.mock.calls.find(
      ([info]) => String(info) === '/api/v1/guest/diary-claim',
    )?.[1];
    const headers = new Headers(claimInit?.headers);
    expect(claimInit?.credentials).toBe('include');
    expect(claimInit?.body).toBeUndefined();
    expect(headers.get('X-XSRF-TOKEN')).toBe('csrf-token');
    expect(headers.get('Authorization')).toBe('Bearer member-token');
  });

  it('claim 응답을 잃어도 이미 회원 소유면 성공으로 이어간다', async (): Promise<void> => {
    routeFetch({
      [CSRF]: [csrf],
      [CLAIM]: [networkError],
      [MEMBER_DIARY]: [async () => jsonResponse(memberDiary)],
    });

    await expect(resolveGuestTrialClaim(DIARY_ID)).resolves.toMatchObject({
      type: 'claimed',
    });
  });

  it('응답을 잃었고 회원 소유도 아니면 ID를 남기고 다시 시도하게 한다', async (): Promise<void> => {
    routeFetch({
      [CSRF]: [csrf],
      [CLAIM]: [networkError],
      [MEMBER_DIARY]: [
        async () => jsonResponse(problem(404, 'DIARY_NOT_FOUND'), 404),
      ],
    });

    await expect(resolveGuestTrialClaim(DIARY_ID)).resolves.toEqual({
      type: 'retryable',
      errorCode: 'NETWORK_ERROR',
    });
  });

  it('claim 404는 회원 소유 조회 없이 무시할 결과로 돌려준다', async (): Promise<void> => {
    routeFetch({
      [CSRF]: [csrf],
      [CLAIM]: [
        async () =>
          jsonResponse(problem(404, 'GUEST_DIARY_CLAIM_NOT_FOUND'), 404),
      ],
    });

    await expect(resolveGuestTrialClaim(DIARY_ID)).resolves.toEqual({
      type: 'ignored',
      errorCode: 'GUEST_DIARY_CLAIM_NOT_FOUND',
    });
    expect(
      fetchMock.mock.calls.some(
        ([info]) => String(info) === `/api/v1/diaries/${DIARY_ID}`,
      ),
    ).toBe(false);
  });

  it.each([
    ['GUEST_DIARY_ALREADY_CLAIMED', 'alreadyClaimed'],
    ['GUEST_DIARY_CLAIM_LIMIT_EXCEEDED', 'limitExceeded'],
  ])(
    '409 %s는 재시도 없는 종료 오류다',
    async (code, reason): Promise<void> => {
      routeFetch({
        [CSRF]: [csrf],
        [CLAIM]: [async () => jsonResponse(problem(409, code), 409)],
      });

      await expect(resolveGuestTrialClaim(DIARY_ID)).resolves.toMatchObject({
        type: 'terminal',
        reason,
        errorCode: code,
      });
    },
  );

  it('CSRF 오류는 토큰을 다시 받아 한 번만 재시도한다', async (): Promise<void> => {
    routeFetch({
      [CSRF]: [csrf, csrf],
      [CLAIM]: [
        async () => jsonResponse(problem(403, 'INVALID_CSRF_TOKEN'), 403),
        async () => jsonResponse(problem(403, 'INVALID_CSRF_TOKEN'), 403),
      ],
    });

    await expect(resolveGuestTrialClaim(DIARY_ID)).resolves.toEqual({
      type: 'retryable',
      errorCode: 'INVALID_CSRF_TOKEN',
    });
    expect(
      fetchMock.mock.calls.filter(
        ([info]) => String(info) === '/api/v1/guest/diary-claim',
      ),
    ).toHaveLength(2);
  });

  it('약관 동의가 필요하면 동의 화면으로 보낼 결과를 돌려준다', async (): Promise<void> => {
    routeFetch({
      [CSRF]: [csrf],
      [CLAIM]: [
        async () =>
          jsonResponse(problem(403, 'TERMS_ACCEPTANCE_REQUIRED'), 403),
      ],
    });

    await expect(resolveGuestTrialClaim(DIARY_ID)).resolves.toEqual({
      type: 'terms',
      errorCode: 'TERMS_ACCEPTANCE_REQUIRED',
    });
  });

  it('일시 서버 오류는 ID를 남기고 다시 시도하게 한다', async (): Promise<void> => {
    routeFetch({
      [CSRF]: [csrf],
      [CLAIM]: [
        async () => jsonResponse(problem(503, 'SERVICE_UNAVAILABLE'), 503),
      ],
    });

    await expect(resolveGuestTrialClaim(DIARY_ID)).resolves.toEqual({
      type: 'retryable',
      errorCode: 'SERVICE_UNAVAILABLE',
    });
  });
});

describe('체험 생성 접수', (): void => {
  it('접수 응답을 기다리지 않고 일기·작업 ID를 돌려준다', async (): Promise<void> => {
    routeFetch({
      [CSRF]: [csrf],
      'POST /api/v1/guest/diaries': [
        async () =>
          jsonResponse(
            {
              id: DIARY_ID,
              diaryDate: '2026-11-06',
              sourceText: '친구가 민트초코에 치즈폼을 올려 먹어보라고 했다.',
              createdAt: '2026-11-06T10:00:00Z',
              generation: {
                id: GENERATION_ID,
                status: 'PENDING',
                title: null,
                imageUrl: null,
                imageUrlExpiresAt: null,
                completedAt: null,
              },
            },
            202,
          ),
      ],
    });

    await expect(
      submitGuestTrialDiary({
        request: {
          diaryDate: '2026-11-06',
          sourceText: '친구가 민트초코에 치즈폼을 올려 먹어보라고 했다.',
        },
        idempotencyKey: 'f4b1f0ad-4ad0-4c5b-9c5a-0d3b8e9c1a20',
      }),
    ).resolves.toEqual({ diaryId: DIARY_ID, generationId: GENERATION_ID });
  });

  it('용량 초과는 Problem Details 오류로 전달한다', async (): Promise<void> => {
    routeFetch({
      [CSRF]: [csrf],
      'POST /api/v1/guest/diaries': [
        async () =>
          jsonResponse(problem(503, 'GENERATION_CAPACITY_EXCEEDED'), 503),
      ],
    });

    const result = submitGuestTrialDiary({
      request: {
        diaryDate: '2026-11-06',
        sourceText: '열 글자가 넘는 이야기예요.',
      },
      idempotencyKey: 'f4b1f0ad-4ad0-4c5b-9c5a-0d3b8e9c1a20',
    });

    await expect(result).rejects.toBeInstanceOf(RequestError);
  });
});
