import { useCallback, useEffect, useMemo, useState } from 'react';
import { type ApiRequest } from '../../../shared/api';
import {
  getToday,
  isNonNegativeInteger,
  isRecord,
} from '../../../shared/utils';
import {
  getCurrentStreak,
  type CurrentStreak,
} from '../../../domain/currentStreak';
import { CURRENT_STREAK_CACHE_KEY } from '../../../shared/constants';

const useCurrentStreak = () => {
  const [request, setRequest] = useState<ApiRequest<CurrentStreak>>({
    status: 'idle',
  });

  const todayKey = getTodayKey();
  const cachedStreak = useMemo(
    () => readCurrentStreakCache(todayKey),
    [todayKey],
  );

  const execute = useCallback(
    async (cache: CurrentStreak | null) => {
      if (cache !== null) {
        setRequest({
          status: 'success',
          data: cache,
        });
        return;
      }

      setRequest({
        status: 'loading',
      });

      try {
        const response = await getCurrentStreak();

        setRequest({
          status: 'success',
          data: response,
        });

        writeCurrentStreakCache(todayKey, {
          streakCount: response.streakCount,
          recordedToday: response.recordedToday,
        });
      } catch (error: unknown) {
        if (error instanceof Error) {
          setRequest({
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
    void execute(cachedStreak);
  }, [cachedStreak, execute]);

  return { request, execute };
};

export default useCurrentStreak;

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

interface CurrentStreakCache extends CurrentStreak {
  date: string;
  recordedToday: true;
}

const isCurrentStreakCache = (value: unknown): value is CurrentStreakCache => {
  return (
    isRecord(value) &&
    typeof value.date === 'string' &&
    typeof value.streakCount === 'number' &&
    isNonNegativeInteger(value.streakCount) &&
    value.recordedToday === true
  );
};
