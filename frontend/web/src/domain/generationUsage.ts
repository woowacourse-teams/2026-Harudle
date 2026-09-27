import { ERROR_MESSAGES } from '../shared/errorMessage';
import { API_BASE_URL, isProblemDetails, RequestError } from '../shared/api';
import { authFetch } from '../shared/auth';
import { isNonNegativeInteger } from '../shared/utils';

export const getGenerationUsage = async ({
  signal,
}: {
  signal?: AbortSignal;
} = {}): Promise<GenerationUsageResponse> => {
  const response = await authFetch(`${API_BASE_URL}/me/generation-usage`, {
    signal,
  });

  if (!response.ok) {
    const errorData: unknown = await response.json().catch(() => null);

    if (isProblemDetails(errorData)) {
      throw new RequestError(errorData);
    }

    throw new Error(ERROR_MESSAGES.GENERATION_USAGE_FETCH_FAILED);
  }

  const data: unknown = await response.json();

  if (!isGenerationUsageResponse(data)) {
    throw new Error(ERROR_MESSAGES.INVALID_GENERATION_USAGE_RESPONSE);
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
    isNonNegativeInteger(value.usedCount) &&
    'limitCount' in value &&
    typeof value.limitCount === 'number' &&
    isNonNegativeInteger(value.limitCount) &&
    'remainingCount' in value &&
    typeof value.remainingCount === 'number' &&
    isNonNegativeInteger(value.remainingCount)
  );
};
