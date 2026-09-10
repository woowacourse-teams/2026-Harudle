import { afterEach, describe, expect, it, jest } from '@jest/globals';
import { RequestError, type ProblemDetails } from '../../shared/api';
import { getSharedDiary } from './sharedDiary';

const fetchMock = jest.fn<typeof fetch>();
const originalFetch = globalThis.fetch;

const SHARE_ID = '06ed972e-0b79-4da0-9716-c9bd8faec85d';

const sharedDiaryResponse = {
  title: '비가 와도, 나는 괜찮았다.',
  diaryDate: '2026-08-12',
  imageUrl: 'https://example.com/diary.png',
  imageUrlExpiresAt: '2026-08-12T20:20:23+09:00',
  createdAt: '2026-08-12T20:10:23+09:00',
};

const problemDetails: ProblemDetails = {
  type: 'https://api.harudle.example/problems/share-not-found',
  title: 'Share not found',
  status: 404,
  detail: '공유 링크를 찾을 수 없습니다.',
  instance: `/api/v1/public/shares/${SHARE_ID}`,
  code: 'SHARE_NOT_FOUND',
  traceId: 'trace-id',
};

const createJsonResponse = (data: unknown, status: number): Response =>
  ({
    ok: status >= 200 && status < 300,
    status,
    json: async () => data,
  }) as Response;

afterEach(() => {
  globalThis.fetch = originalFetch;
  fetchMock.mockReset();
});

describe('공유 일기 조회 API', () => {
  it('조회에 성공하면 공유 일기를 반환한다', async () => {
    globalThis.fetch = fetchMock.mockResolvedValueOnce(
      createJsonResponse(sharedDiaryResponse, 200),
    );

    await expect(getSharedDiary({ shareId: SHARE_ID })).resolves.toEqual(
      sharedDiaryResponse,
    );

    expect(fetchMock).toHaveBeenCalledTimes(1);
    expect(fetchMock).toHaveBeenCalledWith(`/api/v1/public/shares/${SHARE_ID}`);
  });

  it('성공 응답 형식이 잘못되면 검증 오류를 던진다', async () => {
    globalThis.fetch = fetchMock.mockResolvedValueOnce(
      createJsonResponse({ ...sharedDiaryResponse, imageUrl: null }, 200),
    );

    await expect(getSharedDiary({ shareId: SHARE_ID })).rejects.toThrow(
      'SharedDiary 응답 형식이 일치하지 않습니다.',
    );
  });

  it('조회에 실패하면 RequestError를 던진다', async () => {
    globalThis.fetch = fetchMock.mockResolvedValueOnce(
      createJsonResponse(problemDetails, problemDetails.status),
    );

    await expect(getSharedDiary({ shareId: SHARE_ID })).rejects.toBeInstanceOf(
      RequestError,
    );
  });

  it('실패 응답이 Problem Details 형식이 아니면 기본 오류를 던진다', async () => {
    globalThis.fetch = fetchMock.mockResolvedValueOnce(
      createJsonResponse({ message: 'Internal Server Error' }, 500),
    );

    await expect(getSharedDiary({ shareId: SHARE_ID })).rejects.toThrow(
      '알 수 없는 에러가 발생했습니다.',
    );
  });

  it('실패 응답을 JSON으로 파싱할 수 없으면 기본 오류를 던진다', async () => {
    globalThis.fetch = fetchMock.mockResolvedValueOnce({
      ...createJsonResponse(null, 500),
      json: async () => {
        throw new SyntaxError('Invalid JSON');
      },
    });

    await expect(getSharedDiary({ shareId: SHARE_ID })).rejects.toThrow(
      '알 수 없는 에러가 발생했습니다.',
    );
  });
});
