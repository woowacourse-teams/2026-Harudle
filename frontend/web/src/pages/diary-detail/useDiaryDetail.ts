import { useCallback, useEffect, useState } from 'react';
import type { ApiRequest } from '../../shared/api';
import {
  getDiaryDetail,
  type DiaryDetailResponse,
} from '../../domain/diary/diaryDetail';
import { useAnalytics } from '../../posthog/useAnalytics';

const useDiaryDetail = ({ diaryId }: { diaryId: string }) => {
  const { track } = useAnalytics();
  const [request, setRequest] = useState<ApiRequest<DiaryDetailResponse>>({
    status: 'idle',
  });

  const execute = useCallback(async (): Promise<void> => {
    setRequest({
      status: 'loading',
    });
    try {
      const diaryDetailResponse = await getDiaryDetail({ diaryId });

      setRequest({
        status: 'success',
        data: diaryDetailResponse,
      });

      track('diary_detail_viewed', {
        diary_id: diaryDetailResponse.id,
        diary_date: diaryDetailResponse.diaryDate,
      });
    } catch (error: unknown) {
      if (error instanceof Error) {
        setRequest({
          status: 'error',
          error: error,
        });
      }
    }
  }, [diaryId, track]);

  useEffect(() => {
    // TODO: API 요청과 상태 갱신 책임을 분리해 lint 예외를 제거한다.
    // eslint-disable-next-line react-hooks/set-state-in-effect
    void execute();
  }, [execute]);

  return { request };
};

export default useDiaryDetail;
