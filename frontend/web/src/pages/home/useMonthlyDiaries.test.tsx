import { beforeEach, describe, expect, it, jest } from '@jest/globals';
import { renderHook, waitFor } from '@testing-library/react';
import useMonthlyDiaries from './useMonthlyDiaries';

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

describe('useMonthlyDiaries', () => {
  beforeEach(() => {
    mockAuthFetch.mockReset();
    mockTrack.mockReset();
  });

  it('월별 일기 목록 조회가 성공하면 타임라인 조회 이벤트를 기록한다', async () => {
    mockAuthFetch.mockResolvedValueOnce(
      createJsonResponse({
        year: 2026,
        month: 8,
        days: [
          {
            date: '2026-08-24',
            exist: true,
            items: [
              {
                id: 'diary-1',
                title: '오늘의 일기',
                thumbnailUrl: 'https://example.com/diary.png',
              },
            ],
          },
        ],
      }),
    );

    renderHook(() => useMonthlyDiaries({ year: 2026, month: 8 }));

    await waitFor(() => {
      expect(mockTrack).toHaveBeenCalledWith('diary_timeline_viewed', {
        year: 2026,
        month: 8,
        diary_count: 1,
        has_diaries: true,
      });
    });
  });
});
