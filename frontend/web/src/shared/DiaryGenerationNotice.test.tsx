import {
  afterEach,
  beforeEach,
  describe,
  expect,
  it,
  jest,
} from '@jest/globals';
import { act, render } from '@testing-library/react';
import DiaryGenerationNotice from './DiaryGenerationNotice';
import type { getDiaryGenerationStatus } from '../domain/diary/diaryGenerate';
import { AuthenticationRequiredError } from './auth';
import {
  DIARY_CONTENT_SESSION_KEY,
  DIARY_GENERATION_TOAST_ID_KEY,
} from './constants';

const mockGetStatus = jest.fn<typeof getDiaryGenerationStatus>();
const mockAddToast = jest.fn<() => string>();
const mockDeleteToast = jest.fn();
const mockUpdateToast = jest.fn();
const mockNavigate = jest.fn();
let mockGenerationId: string | null = 'generation-1';
const mockRemoveGenerationId = jest.fn((): void => {
  mockGenerationId = null;
});

jest.mock('react-router', () => ({
  useNavigate: () => mockNavigate,
}));
jest.mock('./useDiaryGenerationId', () => ({
  __esModule: true,
  default: (): string | null => mockGenerationId,
}));
jest.mock('../domain/diary/diaryGenerationStore', () => ({
  getGenerationId: (): string | null => mockGenerationId,
  removeGenerationId: (): void => mockRemoveGenerationId(),
}));
jest.mock('../domain/diary/diaryGenerate', () => ({
  getDiaryGenerationStatus: (
    ...args: Parameters<typeof getDiaryGenerationStatus>
  ) => mockGetStatus(...args),
}));
jest.mock('./ToastContext', () => ({
  useToastContext: () => ({
    addToast: mockAddToast,
    deleteToast: mockDeleteToast,
    updateToast: mockUpdateToast,
  }),
}));

const renderNotice = (): ReturnType<typeof render> =>
  render(<DiaryGenerationNotice />);

beforeEach((): void => {
  jest.useFakeTimers();
  mockGenerationId = 'generation-1';
  mockGetStatus.mockReset();
  mockAddToast.mockReset().mockReturnValue('generation-toast');
  mockDeleteToast.mockReset();
  mockUpdateToast.mockReset();
  mockRemoveGenerationId.mockClear();
  localStorage.clear();
  sessionStorage.clear();
  sessionStorage.setItem(DIARY_CONTENT_SESSION_KEY, '작성 중인 내용');
});

afterEach((): void => {
  jest.clearAllTimers();
  jest.useRealTimers();
});

describe('생성 알림을 중단한 뒤 도착한 응답', (): void => {
  it('진행 중 토스트만 지우고 늦은 성공 응답이 기록이나 안내를 바꾸지 않는다', async (): Promise<void> => {
    let finish:
      | ((
          response: Awaited<ReturnType<typeof getDiaryGenerationStatus>>,
        ) => void)
      | undefined;
    mockGetStatus.mockImplementationOnce(
      (): ReturnType<typeof getDiaryGenerationStatus> =>
        new Promise((resolve): void => {
          finish = resolve;
        }),
    );
    const { unmount } = renderNotice();
    await act(async (): Promise<void> => {
      jest.advanceTimersByTime(5_000);
    });
    unmount();
    await act(async (): Promise<void> => {
      finish?.({
        id: 'generation-1',
        status: 'SUCCEEDED',
        diaryId: 'diary-1',
        errorCode: null,
      });
    });

    expect(mockDeleteToast).toHaveBeenCalledWith('generation-toast');
    expect(mockUpdateToast).not.toHaveBeenCalled();
    expect(mockRemoveGenerationId).not.toHaveBeenCalled();
    expect(mockGenerationId).toBe('generation-1');
    expect(localStorage.getItem(DIARY_GENERATION_TOAST_ID_KEY)).toBeNull();
    expect(sessionStorage.getItem(DIARY_CONTENT_SESSION_KEY)).toBe(
      '작성 중인 내용',
    );
    expect(mockAddToast).toHaveBeenCalledTimes(1);
  });

  it('늦은 인증 실패 응답으로 생성 기록을 지우거나 오류 토스트를 남기지 않는다', async (): Promise<void> => {
    let fail: ((error: Error) => void) | undefined;
    mockGetStatus.mockImplementationOnce(
      (): ReturnType<typeof getDiaryGenerationStatus> =>
        new Promise((_, reject): void => {
          fail = reject;
        }),
    );
    const { unmount } = renderNotice();
    await act(async (): Promise<void> => {
      jest.advanceTimersByTime(5_000);
    });
    unmount();
    await act(async (): Promise<void> => {
      fail?.(new AuthenticationRequiredError());
    });

    expect(mockUpdateToast).not.toHaveBeenCalled();
    expect(mockAddToast).toHaveBeenCalledTimes(1);
    expect(mockRemoveGenerationId).not.toHaveBeenCalled();
    expect(mockGenerationId).toBe('generation-1');
  });

  it.each(['SUCCEEDED', 'FAILED'] as const)(
    '%s 안내를 완료한 뒤 정리할 때는 결과 토스트를 지우지 않는다',
    async (status): Promise<void> => {
      mockGetStatus.mockResolvedValueOnce(
        status === 'SUCCEEDED'
          ? {
              id: 'generation-1',
              status,
              diaryId: 'diary-1',
              errorCode: null,
            }
          : {
              id: 'generation-1',
              status,
              diaryId: null,
              errorCode: 'AI_PROVIDER_ERROR',
            },
      );
      const { unmount } = renderNotice();
      await act(async (): Promise<void> => {
        jest.advanceTimersByTime(5_000);
      });
      unmount();

      expect(mockUpdateToast).toHaveBeenCalledWith(
        'generation-toast',
        expect.objectContaining({
          type: status === 'SUCCEEDED' ? 'success' : 'error',
        }),
      );
      expect(mockRemoveGenerationId).toHaveBeenCalledTimes(1);
      expect(mockDeleteToast).not.toHaveBeenCalled();
    },
  );
});
