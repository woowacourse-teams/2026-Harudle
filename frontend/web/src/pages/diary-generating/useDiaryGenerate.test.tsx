import { beforeEach, describe, expect, it, jest } from '@jest/globals';
import { renderHook, waitFor } from '@testing-library/react';

import useDiaryGenerate from './useDiaryGenerate';

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

describe('useDiaryGenerate', () => {
  beforeEach(() => {
    mockAuthFetch.mockReset();
    mockTrack.mockReset();
    sessionStorage.clear();
  });

  it('일기 생성이 성공하면 생성 완료 이벤트를 기록한다', async () => {
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
        usage: {
          usageDate: '2026-08-24',
          usedCount: 1,
          limitCount: 3,
          remainingCount: 2,
        },
      }),
    );

    renderHook(() =>
      useDiaryGenerate({
        diaryDate: '2026-08-24',
        sourceText: '오늘의 이야기',
      }),
    );

    await waitFor(() => {
      expect(mockTrack).toHaveBeenCalledWith('diary_created', {
        diary_id: 'diary-1',
        diary_date: '2026-08-24',
        remaining_generation_count: 2,
      });
    });
  });
});
