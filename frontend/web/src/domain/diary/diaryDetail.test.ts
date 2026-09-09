import { afterEach, describe, expect, it, jest } from '@jest/globals';
import { RequestError, type ProblemDetails } from '../../shared/api';
import { getDiaryDetail } from './diaryDetail';

const mockAuthFetch = jest.fn<(...args: unknown[]) => Promise<Response>>();

jest.mock('../../shared/auth', () => ({
  authFetch: (...args: unknown[]) => mockAuthFetch(...args),
}));

const DIARY_ID = '00000000-0000-4000-8000-000000000001';

const diaryDetail = {
  id: DIARY_ID,
  diaryDate: '2026-08-12',
  sourceText: '오늘 친구와 카페에 가서 오래 이야기했다.',
  createdAt: '2026-08-12T20:10:23+09:00',
  generation: {
    id: '00000000-0000-4000-8000-000000000002',
    status: 'SUCCEEDED',
    title: '비가 와도, 나는 괜찮았다.',
    imageUrl: 'https://example.com/diary.png',
    imageUrlExpiresAt: '2026-08-12T20:20:23+09:00',
    completedAt: '2026-08-12T20:11:42+09:00',
  },
};

const problemDetails: ProblemDetails = {
  type: 'https://api.harudle.example/problems/diary-not-found',
  title: 'Diary not found',
  status: 404,
  detail: '일기를 찾을 수 없습니다.',
  instance: `/api/v1/diaries/${DIARY_ID}`,
  code: 'DIARY_NOT_FOUND',
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

describe('일기 상세 API', () => {
  it('상세 조회에 성공하면 일기 정보를 반환한다', async () => {
    mockAuthFetch.mockResolvedValueOnce(createJsonResponse(diaryDetail, 200));

    await expect(getDiaryDetail({ diaryId: DIARY_ID })).resolves.toEqual(
      diaryDetail,
    );
  });

  it('상세 조회에 실패하면 RequestError를 던진다', async () => {
    mockAuthFetch.mockResolvedValueOnce(
      createJsonResponse(problemDetails, problemDetails.status),
    );

    await expect(getDiaryDetail({ diaryId: DIARY_ID })).rejects.toBeInstanceOf(
      RequestError,
    );
  });
});
