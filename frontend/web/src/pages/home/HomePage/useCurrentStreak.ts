import { useCallback, useEffect, useMemo, useState } from 'react';
import {
  API_BASE_URL,
  isProblemDetails,
  RequestError,
  type ApiRequest,
} from '../../../shared/api';
import { authFetch } from '../../../shared/auth';
import { getToday } from '../../../shared/utils';

interface DiaryStreakItem {
  id: string;
  title: string;
  thumbnailUrl: string;
}

interface DiaryStreakDay {
  date: string;
  items: DiaryStreakItem[];
}

interface CurrentStreakResponse {
  streakCount: number;
  recordedToday: boolean;
  days: DiaryStreakDay[];
}

export interface CurrentStreak {
  streakCount: number;
  recordedToday: boolean;
}

interface CurrentStreakCache {
  date: string;
  streakCount: number;
  recordedToday: true;
}

const CURRENT_STREAK_CACHE_KEY = 'harudle.current-streak';

const isRecord = (value: unknown): value is Record<string, unknown> => {
  return typeof value === 'object' && value !== null;
};

const isDiaryStreakItem = (value: unknown): value is DiaryStreakItem => {
  return (
    isRecord(value) &&
    typeof value.id === 'string' &&
    typeof value.title === 'string' &&
    typeof value.thumbnailUrl === 'string'
  );
};

const isDiaryStreakDay = (value: unknown): value is DiaryStreakDay => {
  return (
    isRecord(value) &&
    typeof value.date === 'string' &&
    Array.isArray(value.items) &&
    value.items.every(isDiaryStreakItem)
  );
};

const isCurrentStreakResponse = (
  value: unknown,
): value is CurrentStreakResponse => {
  return (
    isRecord(value) &&
    typeof value.streakCount === 'number' &&
    typeof value.recordedToday === 'boolean' &&
    Array.isArray(value.days) &&
    value.days.every(isDiaryStreakDay)
  );
};

const isCurrentStreakCache = (value: unknown): value is CurrentStreakCache => {
  return (
    isRecord(value) &&
    typeof value.date === 'string' &&
    typeof value.streakCount === 'number' &&
    value.streakCount >= 0 &&
    value.recordedToday === true
  );
};

const getTodayKey = (): string => {
  const { year, month, day } = getToday();
  return `${year}-${String(month).padStart(2, '0')}-${String(day).padStart(2, '0')}`;
};

const readCurrentStreakCache = (todayKey: string): CurrentStreak | null => {
  try {
    const rawCache = window.localStorage.getItem(CURRENT_STREAK_CACHE_KEY);
    if (rawCache === null) {
      return null;
    }

    const parsedCache: unknown = JSON.parse(rawCache);
    if (!isCurrentStreakCache(parsedCache) || parsedCache.date !== todayKey) {
      return null;
    }

    return {
      streakCount: parsedCache.streakCount,
      recordedToday: parsedCache.recordedToday,
    };
  } catch {
    return null;
  }
};

const writeCurrentStreakCache = (
  todayKey: string,
  currentStreak: CurrentStreak,
): void => {
  if (!currentStreak.recordedToday) {
    return;
  }

  try {
    const cache: CurrentStreakCache = {
      date: todayKey,
      streakCount: currentStreak.streakCount,
      recordedToday: true,
    };
    window.localStorage.setItem(
      CURRENT_STREAK_CACHE_KEY,
      JSON.stringify(cache),
    );
  } catch {
    // 저장소를 사용할 수 없는 환경에서도 API 조회는 계속한다.
  }
};

const useCurrentStreak = () => {
  const [currentStreakRequest, setCurrentStreakRequest] = useState<
    ApiRequest<CurrentStreak>
  >({
    status: 'idle',
  });

  const todayKey = getTodayKey();
  const cachedStreak = useMemo(
    () => readCurrentStreakCache(todayKey),
    [todayKey],
  );

  const getCurrentStreak = useCallback(
    async (cache: CurrentStreak | null) => {
      if (cache !== null) {
        setCurrentStreakRequest({
          status: 'success',
          data: cache,
        });
        return;
      }

      setCurrentStreakRequest({
        status: 'loading',
      });

      try {
        const response = await authFetch(
          `${API_BASE_URL}/diaries/current-streak`,
        );

        if (!response.ok) {
          const errorData = await response.json();
          if (isProblemDetails(errorData)) {
            throw new RequestError(errorData);
          }

          throw new Error('알 수 없는 에러가 발생했습니다.');
        }

        const data: unknown = await response.json();

        if (!isCurrentStreakResponse(data)) {
          throw new Error('CurrentStreak 응답 형식이 일치하지 않습니다.');
        }

        const currentStreak = {
          streakCount: data.streakCount,
          recordedToday: data.recordedToday,
        };

        setCurrentStreakRequest({
          status: 'success',
          data: currentStreak,
        });

        writeCurrentStreakCache(todayKey, currentStreak);
      } catch (error: unknown) {
        if (error instanceof Error) {
          setCurrentStreakRequest({
            status: 'error',
            error,
          });
        }
      }
    },
    [todayKey],
  );

  useEffect(() => {
    // TODO: API 요청과 상태 갱신 책임을 분리해 lint 예외를 제거한다.
    // eslint-disable-next-line react-hooks/set-state-in-effect
    void getCurrentStreak(cachedStreak);
  }, [cachedStreak, getCurrentStreak]);

  return { currentStreakRequest, getCurrentStreak };
};

export default useCurrentStreak;
