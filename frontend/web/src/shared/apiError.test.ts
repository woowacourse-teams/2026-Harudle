import { describe, expect, it, jest } from '@jest/globals';
import { ApiError, createApiError, toUserError } from './apiError';

const createResponse = (
  status: number,
  body: unknown,
  rejectsJson = false,
): Response => {
  return {
    status,
    ok: status >= 200 && status < 300,
    json: rejectsJson
      ? jest.fn<() => Promise<unknown>>().mockRejectedValue(new SyntaxError())
      : jest.fn<() => Promise<unknown>>().mockResolvedValue(body),
  } as unknown as Response;
};

describe('createApiError', () => {
  it('Problem Details의 code에 맞는 사용자 메시지를 만든다', async () => {
    const error = await createApiError(
      createResponse(404, {
        code: 'DIARY_NOT_FOUND',
        status: 404,
        traceId: 'trace-id',
      }),
      '일기를 불러오지 못했습니다.',
    );

    expect(error).toBeInstanceOf(ApiError);
    expect(error.message).toBe('일기를 찾을 수 없습니다.');
    expect(error.code).toBe('DIARY_NOT_FOUND');
    expect(error.status).toBe(404);
    expect(error.traceId).toBe('trace-id');
  });

  it('알 수 없는 code에는 화면별 기본 메시지를 사용한다', async () => {
    const error = await createApiError(
      createResponse(418, { code: 'UNKNOWN_ERROR', status: 418 }),
      '요청을 처리하지 못했습니다.',
    );

    expect(error.message).toBe('요청을 처리하지 못했습니다.');
    expect(error.code).toBe('UNKNOWN_ERROR');
  });

  it('Problem Details가 아니면 HTTP 상태와 기본 메시지를 사용한다', async () => {
    const error = await createApiError(
      createResponse(502, null, true),
      '요청을 처리하지 못했습니다.',
    );

    expect(error.message).toBe('요청을 처리하지 못했습니다.');
    expect(error.code).toBe('HTTP_502');
    expect(error.status).toBe(502);
  });
});

describe('toUserError', () => {
  it('네트워크 오류를 사용자가 이해할 수 있는 메시지로 바꾼다', () => {
    const error = toUserError(
      new TypeError('Failed to fetch'),
      '요청을 처리하지 못했습니다.',
    );

    expect(error.message).toBe('네트워크 연결을 확인해주세요.');
  });

  it('예상하지 못한 오류는 화면별 기본 메시지로 감춘다', () => {
    const error = toUserError(
      new SyntaxError('Unexpected token'),
      '일기를 불러오지 못했습니다.',
    );

    expect(error.message).toBe('일기를 불러오지 못했습니다.');
  });
});
