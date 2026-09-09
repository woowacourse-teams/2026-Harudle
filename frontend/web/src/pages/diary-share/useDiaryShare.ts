import { useCallback, useEffect, useState } from 'react';
import {
  getSharedDiary,
  type SharedDiaryResponse,
} from '../../domain/diary/sharedDiary';
import type { ApiRequest } from '../../shared/api';

const useDiaryShare = ({ shareId }: { shareId: string | undefined }) => {
  const [request, setRequest] = useState<ApiRequest<SharedDiaryResponse>>({
    status: 'idle',
  });

  const execute = useCallback(async (): Promise<void> => {
    setRequest({
      status: 'loading',
    });
    try {
      const sharedDiaryResponse = await getSharedDiary({ shareId });

      setRequest({
        status: 'success',
        data: sharedDiaryResponse,
      });
    } catch (error: unknown) {
      if (error instanceof Error) {
        setRequest({
          status: 'error',
          error: error,
        });
      }
    }
  }, [shareId]);

  useEffect(() => {
    // TODO: API 요청과 상태 갱신 책임을 분리해 lint 예외를 제거한다.
    // eslint-disable-next-line react-hooks/set-state-in-effect
    void execute();
  }, [execute]);

  return { request };
};

export default useDiaryShare;
