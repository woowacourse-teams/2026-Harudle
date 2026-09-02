import { useCallback, useEffect, useState } from 'react';
import { type ApiRequest } from '../../../shared/api';
import { useAnalytics } from '../../../shared/useAnalytics';
import {
  getMonthlyDiaries,
  type MonthlyDiariesResponse,
} from '../../../domain/diary/monthlyDiaries';
import type { Month } from '../../../shared/utils';

const useMonthlyDiaries = ({ year, month }: { year: number; month: Month }) => {
  const { track } = useAnalytics();
  const [request, setRequest] = useState<ApiRequest<MonthlyDiariesResponse>>({
    status: 'idle',
  });

  const execute = useCallback(
    async ({ showLoading = true }: { showLoading: boolean }): Promise<void> => {
      if (showLoading) {
        setRequest({
          status: 'loading',
        });
      }

      try {
        const monthlyDiariesResponse = await getMonthlyDiaries({ year, month });
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
  }, [execute]);

  const refetch = useCallback(
    ({ showLoading }: { showLoading: boolean }) => {
      void execute({ showLoading });
    },
    [execute],
  );

  return { request, refetch };
};

export default useMonthlyDiaries;
