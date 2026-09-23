import {
  afterEach,
  beforeEach,
  describe,
  expect,
  it,
  jest,
} from '@jest/globals';
import { renderHook, waitFor } from '@testing-library/react';
import { getCurrentStreak } from '../../../domain/currentStreak';
import { CURRENT_STREAK_CACHE_KEY } from '../../../shared/constants';
import useCurrentStreak from './useCurrentStreak';

jest.mock('../../../domain/currentStreak');

const mockGetCurrentStreak = jest.mocked(getCurrentStreak);
const today = '2026-09-12';
const cache = { date: today, streakCount: 3, recordedToday: true };
const response = { streakCount: 4, recordedToday: true, days: [] };

describe('useCurrentStreak 캐시 정책', () => {
  beforeEach(() => {
    jest.useFakeTimers({ now: new Date(`${today}T12:00:00+09:00`) });
    localStorage.clear();
    mockGetCurrentStreak.mockReset();
    mockGetCurrentStreak.mockResolvedValue(response);
  });

  afterEach(() => {
    localStorage.clear();
    jest.useRealTimers();
  });

  it('오늘의 유효한 캐시가 있으면 API 호출 없이 성공 상태가 된다', () => {
    localStorage.setItem(CURRENT_STREAK_CACHE_KEY, JSON.stringify(cache));

    const { result } = renderHook(() => useCurrentStreak());

    expect(result.current.request).toEqual({
      status: 'success',
      data: { streakCount: 3, recordedToday: true },
    });
    expect(mockGetCurrentStreak).not.toHaveBeenCalled();
  });

  it.each([
    ['캐시 없음', null],
    ['지난 날짜', JSON.stringify({ ...cache, date: '2026-09-11' })],
    ['잘못된 JSON', '{'],
    ['잘못된 필드 타입', JSON.stringify({ ...cache, streakCount: '3' })],
    ['소수인 연속 기록', JSON.stringify({ ...cache, streakCount: 3.5 })],
    ['오늘 미기록 캐시', JSON.stringify({ ...cache, recordedToday: false })],
  ])(
    '%s이면 API로 조회하고 오늘 기록한 결과를 캐시에 저장한다',
    async (_, rawCache) => {
      if (rawCache !== null) {
        localStorage.setItem(CURRENT_STREAK_CACHE_KEY, rawCache);
      }

      const { result } = renderHook(() => useCurrentStreak());

      await waitFor(() => {
        expect(result.current.request).toEqual({
          status: 'success',
          data: response,
        });
      });
      expect(mockGetCurrentStreak).toHaveBeenCalledTimes(1);
      expect(
        JSON.parse(localStorage.getItem(CURRENT_STREAK_CACHE_KEY) ?? 'null'),
      ).toEqual({
        date: today,
        streakCount: response.streakCount,
        recordedToday: true,
      });
    },
  );

  it('API 조회에 성공해도 오늘 기록하지 않았으면 캐시에 저장하지 않는다', async () => {
    const unrecordedResponse = { ...response, recordedToday: false };
    mockGetCurrentStreak.mockResolvedValue(unrecordedResponse);

    const { result } = renderHook(() => useCurrentStreak());

    await waitFor(() => {
      expect(result.current.request).toEqual({
        status: 'success',
        data: unrecordedResponse,
      });
    });
    expect(mockGetCurrentStreak).toHaveBeenCalledTimes(1);
    expect(localStorage.getItem(CURRENT_STREAK_CACHE_KEY)).toBeNull();
  });
});
