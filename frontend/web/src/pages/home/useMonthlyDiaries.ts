import { useCallback, useEffect, useRef, useState } from 'react';
import { type ApiRequest } from '../../shared/api';
import {
  getMonthlyDiaries,
  type MonthlyDiariesResponse,
} from '../../domain/diary/monthlyDiaries';
import type { Month } from '../../shared/utils';
import { useAnalytics } from '../../posthog/useAnalytics';

const useMonthlyDiaries = ({ year, month }: { year: number; month: Month }) => {
  const { track } = useAnalytics();
  const [request, setRequest] = useState<ApiRequest<MonthlyDiariesResponse>>({
    status: 'idle',
  });
  const abortControllerRef = useRef<AbortController | null>(null);

  const execute = useCallback(
    async ({ showLoading = true }: { showLoading: boolean }): Promise<void> => {
      // 최초 조회와 refetch가 겹쳐도 최신 요청만 반영하도록 이전 요청을 취소한다.
      abortControllerRef.current?.abort();
      abortControllerRef.current = new AbortController();
      const { signal } = abortControllerRef.current;

      if (showLoading) {
        setRequest({
          status: 'loading',
        });
      }

      try {
        const monthlyDiariesResponse = await getMonthlyDiaries({
          year,
          month,
          signal,
        });
        setRequest({
          status: 'success',
          data: monthlyDiariesResponse,
        });

        const diaryCount = monthlyDiariesResponse.days.reduce(
          (count, day) => count + day.items.length,
          0,
        );

        track('diary_timeline_viewed', {
          year: monthlyDiariesResponse.year,
          month: monthlyDiariesResponse.month,
          diary_count: diaryCount,
          has_diaries: diaryCount > 0,
        });
      } catch (error: unknown) {
        if (signal.aborted) {
          return;
        }
        if (error instanceof Error) {
          setRequest({
            status: 'error',
            error: error,
          });
        }
      }
    },
    [year, month, track],
  );

  useEffect(() => {
    // TODO: API 요청과 상태 갱신 책임을 분리해 lint 예외를 제거한다.
    // eslint-disable-next-line react-hooks/set-state-in-effect
    void execute({ showLoading: true });

    return () => {
      abortControllerRef.current?.abort();
    };
  }, [execute]);

  const refetch = useCallback(() => {
    void execute({ showLoading: false });
  }, [execute]);

  return { request, refetch };
};

export default useMonthlyDiaries;
