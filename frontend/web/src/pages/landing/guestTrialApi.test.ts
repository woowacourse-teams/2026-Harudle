import { afterEach, describe, expect, it, jest } from '@jest/globals';
import { GENERATION_FAILURE_CODES } from '../../domain/diary/diaryGeneration';
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

const pendingGuestDiaryResponse = {
  ...guestDiaryResponse,
  generation: {
    id: guestDiaryResponse.generation.id,
    status: 'PENDING',
    title: null,
    imageUrl: null,
    imageUrlExpiresAt: null,
    completedAt: null,
  },
} as const;

const pendingStatusResponse = {
  id: guestDiaryResponse.generation.id,
  status: 'PENDING',
  diaryId: null,
  errorCode: null,
} as const;

const succeededStatusResponse = {
  ...pendingStatusResponse,
  status: 'SUCCEEDED',
  diaryId: guestDiaryResponse.id,
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
  fetchMock.mockImplementation((): Promise<Response> => {
    const response = responses.shift();

    if (response === undefined) {
      return Promise.reject(new Error('준비된 fetch 응답이 없습니다.'));
    }

    return Promise.resolve(response);
  });

  return fetchMock;
};

afterEach(() => {
  globalThis.fetch = originalFetch;
  fetchMock.mockReset();
  jest.restoreAllMocks();
  jest.useRealTimers();
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

  it.each([202, 200])(
    '%i 생성 응답이 대기 중이면 완료될 때까지 조회한다',
    async (status) => {
      jest.useFakeTimers();
      const fetchSpy = mockFetch([
        createResponse({ token: 'csrf-token' }, 200),
        createResponse(pendingGuestDiaryResponse, status),
        createResponse({ ...pendingStatusResponse, status: 'PROCESSING' }, 200),
        createResponse(succeededStatusResponse, 200),
        createResponse(guestDiaryResponse, 200),
      ]);
      const result = createGuestDiary({
        request: {
          diaryDate: guestDiaryResponse.diaryDate,
          sourceText: guestDiaryResponse.sourceText,
        },
        idempotencyKey: '7e5cc251-fdde-4cc0-a54e-2c8142750609',
      });
      const assertion = expect(result).resolves.toEqual(guestDiaryResponse);

      await jest.runAllTimersAsync();
      await assertion;

      expect(fetchSpy).toHaveBeenCalledTimes(5);
      expect(fetchSpy).toHaveBeenNthCalledWith(
        3,
        `/api/v1/guest/diary-generations/${guestDiaryResponse.generation.id}`,
        { credentials: 'include' },
      );
      expect(fetchSpy).toHaveBeenLastCalledWith(
        `/api/v1/guest/diaries/${guestDiaryResponse.id}`,
        { credentials: 'include' },
      );
    },
  );

  it('결과 조회가 대기 중이면 완성된 결과를 기다린다', async () => {
    jest.useFakeTimers();
    mockFetch([
      createResponse(pendingGuestDiaryResponse, 200),
      createResponse(succeededStatusResponse, 200),
      createResponse(guestDiaryResponse, 200),
    ]);
    const assertion = expect(
      getGuestDiary(guestDiaryResponse.id),
    ).resolves.toEqual(guestDiaryResponse);

    await jest.runAllTimersAsync();
    await assertion;
  });

  it('대기 중 응답의 이미지 필드가 null이 아니면 거부한다', async () => {
    mockFetch([
      createResponse(
        {
          ...pendingGuestDiaryResponse,
          generation: {
            ...pendingGuestDiaryResponse.generation,
            imageUrl: guestDiaryResponse.generation.imageUrl,
          },
        },
        200,
      ),
    ]);

    await expect(getGuestDiary(guestDiaryResponse.id)).rejects.toThrow(
      '게스트 일기 응답 형식이 일치하지 않습니다',
    );
  });

  it('대기 중 조회가 실패하면 폴링을 중단한다', async () => {
    jest.useFakeTimers();
    const fetchSpy = mockFetch([
      createResponse(pendingGuestDiaryResponse, 200),
      createResponse(null, 404),
    ]);
    const assertion = expect(
      getGuestDiary(guestDiaryResponse.id),
    ).rejects.toThrow('게스트 일기 조회에 실패했습니다');

    await jest.runAllTimersAsync();
    await assertion;
    expect(fetchSpy).toHaveBeenCalledTimes(2);
  });

  it('대기 상태가 계속되면 제한 횟수 이후 폴링을 중단한다', async () => {
    jest.useFakeTimers();
    const fetchSpy = mockFetch([
      createResponse(pendingGuestDiaryResponse, 200),
      ...Array.from({ length: 60 }, (): Response =>
        createResponse(pendingStatusResponse, 200),
      ),
    ]);
    const assertion = expect(
      getGuestDiary(guestDiaryResponse.id),
    ).rejects.toThrow('게스트 일기 결과를 불러오지 못했습니다');

    await jest.runAllTimersAsync();
    await assertion;
    expect(fetchSpy).toHaveBeenCalledTimes(61);
  });

  it.each(GENERATION_FAILURE_CODES)(
    '접수 후 %s 실패의 action을 전달하고 일기 조회를 중단한다',
    async (errorCode): Promise<void> => {
      jest.useFakeTimers();
      const action = '체험 생성에 실패했어요. 로그인 후 다시 생성해 주세요.';
      const fetchSpy = mockFetch([
        createResponse(pendingGuestDiaryResponse, 200),
        createResponse(
          { ...pendingStatusResponse, status: 'FAILED', errorCode, action },
          200,
        ),
      ]);
      const result = getGuestDiary(guestDiaryResponse.id);
      const assertion = expect(result).rejects.toThrow(action);
      await jest.runAllTimersAsync();
      await assertion;
      expect(fetchSpy).toHaveBeenCalledTimes(2);
    },
  );

  it.each([undefined, null, '', '   ', 123])(
    '실패 action이 올바르지 않으면 기본 안내로 실패를 전달한다: %j',
    async (action): Promise<void> => {
      jest.useFakeTimers();
      mockFetch([
        createResponse(pendingGuestDiaryResponse, 200),
        createResponse(
          {
            ...pendingStatusResponse,
            status: 'FAILED',
            errorCode: 'AI_CONTENT_BLOCKED',
            action,
          },
          200,
        ),
      ]);
      const assertion = expect(
        getGuestDiary(guestDiaryResponse.id),
      ).rejects.toThrow('게스트 일기 생성에 실패했습니다');
      await jest.runAllTimersAsync();
      await assertion;
    },
  );

  it('잘못된 생성 상태 응답은 거부한다', async (): Promise<void> => {
    jest.useFakeTimers();
    mockFetch([
      createResponse(pendingGuestDiaryResponse, 200),
      createResponse(
        { ...pendingStatusResponse, status: 'FAILED', errorCode: 'UNKNOWN' },
        200,
      ),
    ]);
    const assertion = expect(
      getGuestDiary(guestDiaryResponse.id),
    ).rejects.toThrow('DiaryGenerationStatus 응답 형식이 일치하지 않습니다.');
    await jest.runAllTimersAsync();
    await assertion;
  });

  it('같은 생성 요청을 다시 보냈을 때의 실패 action도 전달한다', async (): Promise<void> => {
    const action = '체험 생성 시간이 초과됐어요. 로그인 후 다시 생성해 주세요.';
    mockFetch([
      createResponse({ token: 'csrf-token' }, 200),
      createResponse(
        {
          type: 'about:blank',
          title: 'Timeout',
          status: 504,
          detail: '시간 초과',
          instance: '/api/v1/guest/diaries',
          code: 'AI_PROVIDER_TIMEOUT',
          action,
        },
        504,
      ),
    ]);
    const result = createGuestDiary({
      request: {
        diaryDate: guestDiaryResponse.diaryDate,
        sourceText: guestDiaryResponse.sourceText,
      },
      idempotencyKey: guestDiaryResponse.generation.id,
    });
    await expect(result).rejects.toThrow(action);
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
