import { describe, expect, it } from '@jest/globals';
import { isProblemDetails, RequestError, type ProblemDetails } from './api';

const problem: ProblemDetails = {
  type: 'about:blank',
  title: 'Service Unavailable',
  status: 503,
  detail: '기존 오류 안내',
  instance: '/api/v1/diaries',
  code: 'GENERATION_CAPACITY_EXCEEDED',
};

describe('RequestError 사용자 안내', (): void => {
  it('action이 있으면 필드 오류와 detail보다 먼저 표시한다', (): void => {
    const action = '현재 요청이 많아요. 잠시 후 다시 시도해 주세요.';
    const response = {
      ...problem,
      action,
      errors: [{ field: 'sourceText', reason: '필드 오류 안내' }],
    };

    expect(isProblemDetails(response)).toBe(true);
    expect(new RequestError(response).message).toBe(action);
  });

  it.each([undefined, null, '', '   ', 123, {}])(
    'action이 안내 문구가 아니면 기존 detail을 표시한다: %j',
    (action): void => {
      const response = { ...problem, action };
      expect(isProblemDetails(response)).toBe(true);
      expect(new RequestError(response).message).toBe(problem.detail);
    },
  );

  it('action이 없으면 기존 필드 오류를 먼저 표시한다', (): void => {
    expect(
      new RequestError({
        ...problem,
        errors: [{ field: 'sourceText', reason: '필드 오류 안내' }],
      }).message,
    ).toBe('필드 오류 안내');
  });

  it('필드 오류가 빈 배열이면 detail을 표시한다', (): void => {
    expect(new RequestError({ ...problem, errors: [] }).message).toBe(
      problem.detail,
    );
  });
});
