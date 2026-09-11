import { API_BASE_URL, isProblemDetails, RequestError } from '../shared/api';
import { authFetch } from '../shared/auth';

export const getGenerationUsage =
  async (): Promise<GenerationUsageResponse> => {
    const response = await authFetch(`${API_BASE_URL}/me/generation-usage`);

    if (!response.ok) {
      const errorData: unknown = await response.json().catch(() => null);

      if (isProblemDetails(errorData)) {
        throw new RequestError(errorData);
      }

      throw new Error(
        '남은 생성 횟수를 조회하는 중 에러가 발생했습니다. 다시 시도해주세요.',
      );
    }

    const data: unknown = await response.json();

    if (!isGenerationUsageResponse(data)) {
      throw new Error('GenerationUsage 응답 형식이 일치하지 않습니다.');
    }

    return data;
  };

export interface GenerationUsageResponse {
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
