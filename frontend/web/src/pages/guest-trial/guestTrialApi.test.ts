import { afterEach, describe, expect, it, jest } from '@jest/globals';
import {
  createGuestDiary,
  createGuestDiaryIdempotencyKey,
  getGuestDiary,
  isGuestDiaryResponse,
  issueGuestSession,
  requestGuestCsrfToken,
} from './guestTrialApi';

const guestDiaryResponse = {
  id: '7e5cc251-fdde-4cc0-a54e-2c8142750609',
  diaryDate: '2026-08-20',
  sourceText: '오늘은 게스트 체험으로 그림 일기를 만들었다.',
  createdAt: '2026-08-20T12:00:00Z',
  generation: {
    id: '3b85d827-72da-4e23-98ef-a97119e441b8',
    status: 'SUCCEEDED',
    title: '처음 만든 그림 일기',
    imageUrl: 'https://example.com/guest-diary.png',
    imageUrlExpiresAt: '2026-08-20T13:00:00Z',
    completedAt: '2026-08-20T12:00:10Z',
  },
} as const;

const createResponse = (data: unknown, status: number): Response => {
  return {
    ok: status >= 200 && status < 300,
    status,
    json: () => Promise.resolve(data),
  } as Response;
};

const fetchMock = jest.fn<typeof fetch>();
const originalFetch = globalThis.fetch;

const mockFetch = (
  responses: Response[],
): jest.MockedFunction<typeof fetch> => {
  globalThis.fetch = fetchMock;
  fetchMock.mockImplementation(() => Promise.resolve(responses.shift()!));

  return fetchMock;
};

afterEach(() => {
  globalThis.fetch = originalFetch;
  fetchMock.mockReset();
  jest.restoreAllMocks();
});

describe('게스트 체험 API', () => {
  it('CSRF Token을 쿠키와 함께 발급한다', async () => {
    const fetchSpy = mockFetch([createResponse({ token: 'csrf-token' }, 200)]);

    await expect(requestGuestCsrfToken()).resolves.toBe('csrf-token');
    expect(fetchSpy).toHaveBeenCalledWith('/api/v1/auth/csrf', {
      credentials: 'include',
    });
  });

  it('CSRF Token을 발급한 후 게스트 세션을 쿠키와 함께 요청한다', async () => {
    const fetchSpy = mockFetch([
      createResponse({ token: 'csrf-token' }, 200),
      createResponse(null, 204),
    ]);

    await expect(issueGuestSession()).resolves.toBeUndefined();
    expect(fetchSpy).toHaveBeenNthCalledWith(2, '/api/v1/guest/session', {
      method: 'POST',
      credentials: 'include',
      headers: {
        'X-XSRF-TOKEN': 'csrf-token',
      },
    });
  });

  it.each([201, 200])(
    '%i 응답의 게스트 일기를 생성 결과로 반환한다',
    async (status) => {
      const fetchSpy = mockFetch([
        createResponse({ token: 'csrf-token' }, 200),
        createResponse(guestDiaryResponse, status),
      ]);
      const request = {
        diaryDate: '2026-08-20',
        sourceText: '오늘은 게스트 체험으로 그림 일기를 만들었다.',
      };

      await expect(
        createGuestDiary({
          request,
          idempotencyKey: '7e5cc251-fdde-4cc0-a54e-2c8142750609',
        }),
      ).resolves.toEqual(guestDiaryResponse);
      expect(fetchSpy).toHaveBeenNthCalledWith(2, '/api/v1/guest/diaries', {
        method: 'POST',
        credentials: 'include',
        headers: {
          'Content-Type': 'application/json',
          'Idempotency-Key': '7e5cc251-fdde-4cc0-a54e-2c8142750609',
          'X-XSRF-TOKEN': 'csrf-token',
        },
        body: JSON.stringify(request),
      });
    },
  );

  it('게스트 일기 조회에 세션 쿠키를 포함한다', async () => {
    const fetchSpy = mockFetch([createResponse(guestDiaryResponse, 200)]);

    await expect(getGuestDiary(guestDiaryResponse.id)).resolves.toEqual(
      guestDiaryResponse,
    );
    expect(fetchSpy).toHaveBeenCalledWith(
      `/api/v1/guest/diaries/${guestDiaryResponse.id}`,
      { credentials: 'include' },
    );
  });

  it('생성 요청에 사용할 UUID를 만든다', () => {
    const randomUUIDSpy = jest
      .spyOn(crypto, 'randomUUID')
      .mockReturnValue('7e5cc251-fdde-4cc0-a54e-2c8142750609');

    expect(createGuestDiaryIdempotencyKey()).toBe(
      '7e5cc251-fdde-4cc0-a54e-2c8142750609',
    );
    expect(randomUUIDSpy).toHaveBeenCalledTimes(1);
  });

  it('게스트 일기 응답 구조가 다르면 거부한다', () => {
    expect(
      isGuestDiaryResponse({
        ...guestDiaryResponse,
        generation: { ...guestDiaryResponse.generation, imageUrl: null },
      }),
    ).toBe(false);
  });
});
