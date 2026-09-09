import { afterEach, describe, expect, it, jest } from '@jest/globals';
import { RequestError, type ProblemDetails } from '../../shared/api';
import { createDiaryShareLink } from './diaryShareLink';

const mockAuthFetch = jest.fn<(...args: unknown[]) => Promise<Response>>();

jest.mock('../../shared/auth', () => ({
  authFetch: (...args: unknown[]) => mockAuthFetch(...args),
}));

const DIARY_ID = '00000000-0000-4000-8000-000000000001';

const diaryShareLinkResponse = {
  shareId: '06ed972e-0b79-4da0-9716-c9bd8faec85d',
  shareUrl: 'http://localhost:5173/shares/06ed972e-0b79-4da0-9716-c9bd8faec85d',
  createdAt: '2026-08-12T20:11:42+09:00',
};

const problemDetails: ProblemDetails = {
  type: 'https://api.harudle.example/problems/diary-share-failed',
  title: 'Diary share failed',
  status: 503,
  detail: '공유 링크를 만들지 못했습니다. 다시 시도해주세요.',
  instance: `/api/v1/diaries/${DIARY_ID}/share-link`,
  code: 'DIARY_SHARE_FAILED',
  traceId: 'trace-id',
};

const createJsonResponse = (data: unknown, status: number): Response =>
  ({
    ok: status >= 200 && status < 300,
    status,
    json: async () => data,
  }) as Response;

afterEach(() => {
  mockAuthFetch.mockReset();
});

describe('일기 공유 링크 생성 API', () => {
  it('공유 링크 생성에 성공하면 공유 정보를 반환한다', async () => {
    mockAuthFetch.mockResolvedValueOnce(
      createJsonResponse(diaryShareLinkResponse, 200),
    );

    await expect(createDiaryShareLink({ diaryId: DIARY_ID })).resolves.toEqual(
      diaryShareLinkResponse,
    );
  });

  it('공유 링크 생성에 실패하면 RequestError를 던진다', async () => {
    mockAuthFetch.mockResolvedValueOnce(
      createJsonResponse(problemDetails, problemDetails.status),
    );

    await expect(
      createDiaryShareLink({ diaryId: DIARY_ID }),
    ).rejects.toBeInstanceOf(RequestError);
  });
});
