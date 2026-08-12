export const API_BASE_URL = '/api/v1';

interface ApiRequestIdle {
  status: 'idle';
}

interface ApiRequestLoading {
  status: 'loading';
}

interface ApiRequestSuccess<T> {
  status: 'success';
  data: T;
}

interface ApiRequestError {
  status: 'error';
  error: Error; //<- 일반 Error도 정규화하는 메서드 만들어서 처리할까? 고민중
}

export type ApiRequest<T> =
  ApiRequestIdle | ApiRequestLoading | ApiRequestSuccess<T> | ApiRequestError;

export type ApiRequestStatus = ApiRequest<void>['status'];
