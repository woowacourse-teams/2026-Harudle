import { API_BASE_URL, isProblemDetails, RequestError } from '../../shared/api';
import { ERROR_MESSAGES } from '../../shared/errorMessage';
import type { DeepReadonly } from '../../shared/typeUtils';
import { isNonNegativeInteger, isRecord } from '../../shared/utils';

interface CategoryData {
  id: string;
  name: string;
  sortOrder: number;
  active: boolean;
}

export type Category = DeepReadonly<CategoryData>;
export type CategoryListResponse = DeepReadonly<CategoryData[]>;

export const getCategoryList = async (): Promise<CategoryListResponse> => {
  const response = await fetch(`${API_BASE_URL}/categories`);

  if (!response.ok) {
    const errorData: unknown = await response.json().catch((): null => null);
    if (isProblemDetails(errorData)) throw new RequestError(errorData);
    throw new Error(ERROR_MESSAGES.CATEGORY_LIST_FETCH_FAILED);
  }

  const data: unknown = await response.json();
  if (!isCategoryListResponse(data)) {
    throw new Error(ERROR_MESSAGES.INVALID_CATEGORY_LIST_RESPONSE);
  }
  return data;
};

const isCategory = (value: unknown): value is Category => {
  return (
    isRecord(value) &&
    typeof value.id === 'string' &&
    typeof value.name === 'string' &&
    typeof value.sortOrder === 'number' &&
    isNonNegativeInteger(value.sortOrder) &&
    typeof value.active === 'boolean'
  );
};

export const isCategoryListResponse = (
  value: unknown,
): value is CategoryListResponse => {
  return Array.isArray(value) && value.every(isCategory);
};
