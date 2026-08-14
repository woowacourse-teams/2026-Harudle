import { useEffect, useState } from 'react';
import { API_BASE_URL, type ApiRequest } from '../../../shared/api';

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
  const [generationUsage, setGenerationUsage] = useState<ApiRequest<number>>({
    status: 'idle',
  });

  useEffect(() => {
    const getRemainingGenerationUsageCard = async (): Promise<void> => {
      setGenerationUsage({
        status: 'loading',
      });

      try {
        const response = await fetch(`${API_BASE_URL}/me/generation-usage`);

        if (!response.ok) {
          throw new Error('네트워크 에러');
        }
        const data: unknown = await response.json();

        if (!isGenerationUsageResponse(data)) {
          throw new Error('GenerationUsage 응답 형식이 일치하지 않습니다.');
        }

        setGenerationUsage({
          status: 'success',
          data: data.remainingCount,
        });
      } catch (error: unknown) {
        if (error instanceof Error) {
          setGenerationUsage({
            status: 'error',
            error: error,
          });
          alert(error.message);
        }
      }
    };

    void getRemainingGenerationUsageCard();
  }, []);

  return { generationUsage };
};

export default useGenrationUsage;
