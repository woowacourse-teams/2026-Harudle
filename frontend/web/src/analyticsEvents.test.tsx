import { beforeEach, describe, expect, it, jest } from '@jest/globals';
import { renderHook, waitFor } from '@testing-library/react';

import useDiaryDetail from './pages/diary-detail/useDiaryDetail';
import useDiaryGenerate from './pages/diary-generating/useDiaryGenerate';
import useMonthlyDiaries from './pages/home/HomePage/useMonthlyDiaries';

const mockTrack = jest.fn<(...args: unknown[]) => void>();
const mockAuthFetch = jest.fn<(...args: unknown[]) => Promise<Response>>();
const mockLocation = {
  state: {
    diaryDate: '2026-08-24',
    sourceText: '오늘의 이야기',
  },
};

jest.mock('./shared/useAnalytics', () => ({
  useAnalytics: () => ({ track: mockTrack }),
}));

jest.mock('./shared/auth', () => ({
  authFetch: (...args: unknown[]) => mockAuthFetch(...args),
}));

jest.mock('react-router', () => ({
  useLocation: () => mockLocation,
}));

const createJsonResponse = (data: unknown): Response =>
  ({
    ok: true,
    status: 200,
    json: async () => data,
  }) as Response;

describe('AU 분석 이벤트', () => {
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

    renderHook(() => useDiaryGenerate());

    await waitFor(() => {
      expect(mockTrack).toHaveBeenCalledWith('diary_created', {
        diary_id: 'diary-1',
        diary_date: '2026-08-24',
        remaining_generation_count: 2,
      });
    });
  });
});
