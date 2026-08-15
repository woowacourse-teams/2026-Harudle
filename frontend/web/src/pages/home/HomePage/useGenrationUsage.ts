import { useEffect, useState } from 'react';
import {
  API_BASE_URL,
  isProblemDetails,
  RequestError,
  type ApiRequest,
} from '../../../shared/api';

interface GenerationUsageResponse {
  usageDate: string;
  usedCount: number;
  limitCount: number;
  remainingCount: number;
}

const isGenerationUsageResponse = (
  value: unknown,
): value is GenerationUsageResponse => {
  return (
    typeof value === 'object' &&
    value !== null &&
    'usageDate' in value &&
    typeof value.usageDate === 'string' &&
    'usedCount' in value &&
    typeof value.usedCount === 'number' &&
    'limitCount' in value &&
    typeof value.limitCount === 'number' &&
    'remainingCount' in value &&
    typeof value.remainingCount === 'number'
  );
};

const useGenrationUsage = () => {
  const [generationUsageRequest, setGenerationUsageRequest] = useState<
    ApiRequest<number>
  >({
    status: 'idle',
  });

  useEffect(() => {
    const getRemainingGenerationUsageCard = async (): Promise<void> => {
      setGenerationUsageRequest({
        status: 'loading',
      });

      try {
        const response = await fetch(`${API_BASE_URL}/me/generation-usage`);

        if (!response.ok) {
          const errorData = await response.json();
          if (isProblemDetails(errorData)) {
            throw new RequestError(errorData);
          }

          throw new Error('알 수 없는 에러가 발생했습니다.');
        }

        const data: unknown = await response.json();

        if (!isGenerationUsageResponse(data)) {
          throw new Error('GenerationUsage 응답 형식이 일치하지 않습니다.');
        }

        setGenerationUsageRequest({
          status: 'success',
          data: data.remainingCount,
        });
      } catch (error: unknown) {
        if (error instanceof Error) {
          setGenerationUsageRequest({
            status: 'error',
            error: error,
          });
        }
      }
    };

    void getRemainingGenerationUsageCard();
  }, []);

  return { generationUsageRequest };
};

export default useGenrationUsage;
