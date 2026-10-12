import { useCallback, useEffect, useState } from 'react';
import {
  getCategoryList,
  type CategoryListResponse,
} from '../../domain/category/categoryList';
import type { ApiRequest } from '../../shared/api';
import { ERROR_MESSAGES } from '../../shared/errorMessage';

interface UseCategoryListResult {
  readonly request: ApiRequest<CategoryListResponse>;
  readonly refetch: () => void;
}

const useCategoryList = (): UseCategoryListResult => {
  const [request, setRequest] = useState<ApiRequest<CategoryListResponse>>({
    status: 'idle',
  });

  const execute = useCallback(async (): Promise<void> => {
    setRequest({ status: 'loading' });

    try {
      const categoryListResponse = await getCategoryList();

      setRequest({
        status: 'success',
        data: categoryListResponse,
      });
    } catch (error: unknown) {
      setRequest({
        status: 'error',
        error:
          error instanceof Error
            ? error
            : new Error(ERROR_MESSAGES.CATEGORY_LIST_FETCH_FAILED),
      });
    }
  }, []);

  useEffect(() => {
    // TODO: API 요청과 상태 갱신 책임을 분리해 lint 예외를 제거한다.
    // eslint-disable-next-line react-hooks/set-state-in-effect
    void execute();
  }, [execute]);

  const refetch = useCallback((): void => {
    void execute();
  }, [execute]);

  return { request, refetch };
};

export default useCategoryList;
