import { useCallback, useEffect, useState } from 'react';
import { type ApiRequest } from '../../../shared/api';
import { getGenerationUsage } from '../../../domain/generationUsage';

const useGenerationUsage = () => {
  const [request, setRequest] = useState<ApiRequest<number>>({
    status: 'idle',
  });

  const execute = useCallback(async (): Promise<void> => {
    setRequest({
      status: 'loading',
    });

    try {
      const response = await getGenerationUsage();

      setRequest({
        status: 'success',
        data: response.remainingCount,
      });
    } catch (error: unknown) {
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
  }, [execute]);

  return { request, execute };
};

export default useGenerationUsage;
