import { describe, expect, it, jest } from '@jest/globals';
import { act, renderHook } from '@testing-library/react';
import { RequestError, type ProblemDetails } from '../shared/api';
import { AuthenticationRequiredError } from '../shared/auth';
import { useErrorTracking } from './useErrorTracking';

const mockCaptureException =
  jest.fn<(error: Error, properties?: Record<string, unknown>) => void>();

jest.mock('@posthog/react', () => ({
  usePostHog: () => ({ captureException: mockCaptureException }),
}));

jest.mock('./posthog', () => ({ isPostHogEnabled: true }));

const createRequestError = (code: string): RequestError => {
  const problem: ProblemDetails = {
    type: 'about:blank',
    title: 'Request failed',
    status: 404,
    detail: '민감한 서버 상세 내용',
    instance: '/api/v1/diaries',
    code,
    traceId: 'trace-123',
    errors: [{ field: 'sourceText', reason: '민감한 필드 오류' }],
  };

  return new RequestError(problem);
};

describe('useErrorTracking', () => {
  it('로그인 경험이 없어 기본 주소로 이동하는 경우는 수집하지 않는다', () => {
    const { result } = renderHook(() => useErrorTracking());

    act(() => {
      result.current.captureError(new AuthenticationRequiredError());
    });

    expect(mockCaptureException).not.toHaveBeenCalled();
  });

  it.each(['INVALID_REFRESH_TOKEN', 'SHARE_NOT_FOUND'])(
    '%s는 수집하지 않는다',
    (code) => {
      const { result } = renderHook(() => useErrorTracking());

      act(() => {
        result.current.captureError(createRequestError(code), {
          feature: 'diary',
          operation: 'read_shared',
        });
      });

      expect(mockCaptureException).not.toHaveBeenCalled();
    },
  );

  it('RequestError의 허용 필드만 안전한 메시지와 함께 수집한다', () => {
    const { result } = renderHook(() => useErrorTracking());

    act(() => {
      result.current.captureError(createRequestError('DIARY_NOT_FOUND'), {
        feature: 'diary',
        operation: 'read_detail',
      });
    });

    const [error, properties] = mockCaptureException.mock.calls[0];
    expect(error.message).toBe('API request failed: DIARY_NOT_FOUND');
    expect(error.message).not.toContain('민감한');
    expect(error.stack).not.toContain('민감한');
    expect(properties).toEqual({
      feature: 'diary',
      operation: 'read_detail',
      error_code: 'DIARY_NOT_FOUND',
      http_status: 404,
      trace_id: 'trace-123',
    });
  });

  it('일반 Error는 기존대로 수집한다', () => {
    const { result } = renderHook(() => useErrorTracking());
    const error = new Error('이미지 로딩 실패');

    act(() => {
      result.current.captureError(error, { feature: 'diary_image' });
    });

    expect(mockCaptureException).toHaveBeenCalledWith(error, {
      feature: 'diary_image',
    });
  });
});
