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
  });

  it('조회에 실패하면 RequestError를 던진다', async () => {
    globalThis.fetch = fetchMock.mockResolvedValueOnce(
      createJsonResponse(problemDetails, problemDetails.status),
    );

    await expect(getSharedDiary({ shareId: SHARE_ID })).rejects.toBeInstanceOf(
      RequestError,
    );
  });
});
