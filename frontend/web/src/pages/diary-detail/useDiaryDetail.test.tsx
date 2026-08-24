import { beforeEach, describe, expect, it, jest } from '@jest/globals';
import { renderHook, waitFor } from '@testing-library/react';

import useDiaryDetail from './useDiaryDetail';

const mockTrack = jest.fn<(...args: unknown[]) => void>();
const mockAuthFetch = jest.fn<(...args: unknown[]) => Promise<Response>>();

jest.mock('../../shared/useAnalytics', () => ({
  useAnalytics: () => ({ track: mockTrack }),
}));

jest.mock('../../shared/auth', () => ({
  authFetch: (...args: unknown[]) => mockAuthFetch(...args),
}));

const createJsonResponse = (data: unknown): Response =>
  ({
    ok: true,
    status: 200,
    json: async () => data,
  }) as Response;

describe('useDiaryDetail', () => {
  beforeEach(() => {
    mockAuthFetch.mockReset();
    mockTrack.mockReset();
  });

  it('일기 상세 조회가 성공하면 상세 조회 이벤트를 기록한다', async () => {
    mockAuthFetch.mockResolvedValueOnce(
      createJsonResponse({
        id: 'diary-1',
        diaryDate: '2026-08-24',
        sourceText: '오늘의 이야기',
        createdAt: '2026-08-24T00:00:00Z',
        generation: {
          id: 'generation-1',
          status: 'SUCCEEDED',
          title: '오늘의 제목',
          imageUrl: 'https://example.com/diary.png',
          imageUrlExpiresAt: '2026-08-25T00:00:00Z',
          completedAt: '2026-08-24T00:00:01Z',
        },
      }),
    );

    renderHook(() => useDiaryDetail({ diaryId: 'diary-1' }));

    await waitFor(() => {
      expect(mockTrack).toHaveBeenCalledWith('diary_detail_viewed', {
        diary_id: 'diary-1',
        diary_date: '2026-08-24',
      });
    });
  });
});
