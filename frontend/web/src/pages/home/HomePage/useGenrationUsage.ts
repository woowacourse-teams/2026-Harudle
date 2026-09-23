import { useCallback, useEffect, useRef, useState } from 'react';
import { type ApiRequest } from '../../../shared/api';
import { getGenerationUsage } from '../../../domain/generationUsage';

const useGenerationUsage = () => {
  const [request, setRequest] = useState<ApiRequest<number>>({
    status: 'idle',
  });
  const abortControllerRef = useRef<AbortController | null>(null);

  const execute = useCallback(async (): Promise<void> => {
    // 최초 조회와 refetch가 겹쳐도 최신 요청만 반영하도록 이전 요청을 취소한다.
    abortControllerRef.current?.abort();
    abortControllerRef.current = new AbortController();
    const { signal } = abortControllerRef.current;

    setRequest({
      status: 'loading',
    });

    try {
      const response = await getGenerationUsage({ signal });

      if (signal.aborted) {
        return;
      }

      setRequest({
        status: 'success',
        data: response.remainingCount,
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
  }, []);

  useEffect(() => {
    // TODO: API 요청과 상태 갱신 책임을 분리해 lint 예외를 제거한다.
    // eslint-disable-next-line react-hooks/set-state-in-effect
    void execute();

    return () => {
      abortControllerRef.current?.abort();
    };
  }, [execute]);

  return { request, execute };
};

export default useGenerationUsage;
