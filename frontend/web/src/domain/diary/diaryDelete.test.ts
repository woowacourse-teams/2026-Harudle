import { ERROR_MESSAGES } from '../../shared/errorMessage';
import { afterEach, describe, expect, it, jest } from '@jest/globals';
import { RequestError, type ProblemDetails } from '../../shared/api';
import { deleteDiary } from './diaryDelete';

const mockAuthFetch = jest.fn<(...args: unknown[]) => Promise<Response>>();

jest.mock('../../shared/auth', () => ({
  authFetch: (...args: unknown[]) => mockAuthFetch(...args),
}));

const DIARY_ID = '00000000-0000-4000-8000-000000000001';

const problemDetails: ProblemDetails = {
  type: 'https://api.harudle.example/problems/diary-not-found',
  title: 'Diary not found',
  status: 404,
  detail: '일기를 찾을 수 없습니다.',
  instance: `/api/v1/diaries/${DIARY_ID}`,
  code: 'DIARY_NOT_FOUND',
  traceId: 'trace-id',
};

const createJsonResponse = (data: unknown, status: number): Response =>
  ({
    ok: status >= 200 && status < 300,
    status,
    json: async () => data,
  }) as Response;

afterEach(() => {
  mockAuthFetch.mockReset();
});

describe('일기 삭제 API', () => {
  it('삭제에 성공하면 정상적으로 완료한다', async () => {
    const json = jest.fn<() => Promise<unknown>>();
    mockAuthFetch.mockResolvedValueOnce({
      ...createJsonResponse(null, 204),
      json,
    });

    await expect(deleteDiary({ diaryId: DIARY_ID })).resolves.toBeUndefined();

    expect(json).not.toHaveBeenCalled();
    expect(mockAuthFetch).toHaveBeenCalledTimes(1);
    expect(mockAuthFetch).toHaveBeenCalledWith(`/api/v1/diaries/${DIARY_ID}`, {
      method: 'DELETE',
    });
  });

  it('삭제에 실패하면 RequestError를 던진다', async () => {
    mockAuthFetch.mockResolvedValueOnce(
      createJsonResponse(problemDetails, problemDetails.status),
    );

    await expect(deleteDiary({ diaryId: DIARY_ID })).rejects.toBeInstanceOf(
      RequestError,
    );
  });

  it('실패 응답이 Problem Details 형식이 아니면 기본 오류를 던진다', async () => {
    mockAuthFetch.mockResolvedValueOnce(
      createJsonResponse({ message: 'Internal Server Error' }, 500),
    );

    await expect(deleteDiary({ diaryId: DIARY_ID })).rejects.toThrow(
      ERROR_MESSAGES.DIARY_DELETION_FAILED,
    );
  });

  it('실패 응답을 JSON으로 파싱할 수 없으면 기본 오류를 던진다', async () => {
    mockAuthFetch.mockResolvedValueOnce({
      ...createJsonResponse(null, 500),
      json: async () => {
        throw new SyntaxError('Invalid JSON');
      },
    });

    await expect(deleteDiary({ diaryId: DIARY_ID })).rejects.toThrow(
      ERROR_MESSAGES.DIARY_DELETION_FAILED,
    );
  });
});
