import {
  afterEach,
  beforeEach,
  describe,
  expect,
  it,
  jest,
} from '@jest/globals';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { RequestError } from '../../shared/api';
import type {
  generateDiary,
  DiaryGenerateResponse,
} from '../../domain/diary/diaryGenerate';
import {
  DIARY_CONTENT_SESSION_KEY,
  DIARY_GENERATION_ID_KEY,
} from '../../shared/constants';
import DiaryWritePage from './DiaryWritePage';
import { DIARY_WRITE_COPY } from './copy';

const mockGenerateDiary = jest.fn<typeof generateDiary>();
const mockTrack = jest.fn();
const mockCaptureError = jest.fn();
const mockNavigate = jest.fn();
const mockAddToast = jest.fn();

jest.mock('../../domain/diary/diaryGenerate', () => ({
  generateDiary: (
    ...args: Parameters<typeof generateDiary>
  ): ReturnType<typeof generateDiary> => mockGenerateDiary(...args),
  DIARY_GENERATION_ERROR_CODE: {
    DAILY_LIMIT_EXCEEDED: 'DAILY_GENERATION_LIMIT_EXCEEDED',
    IN_PROGRESS: 'GENERATION_IN_PROGRESS',
  },
}));
jest.mock('../../posthog/useAnalytics', () => ({
  useAnalytics: () => ({ track: mockTrack }),
}));
jest.mock('../../posthog/useErrorTracking', () => ({
  useErrorTracking: () => ({ captureError: mockCaptureError }),
}));
jest.mock('react-router', () => ({
  useNavigate: () => mockNavigate,
}));
jest.mock('../../shared/ToastContext', () => ({
  useToastContext: () => ({ addToast: mockAddToast }),
}));
jest.mock('../../assets/icons/back.svg', () => 'back.svg');
jest.mock('../../assets/icons/warning.svg', () => 'warning.svg');

const acceptedResponse: DiaryGenerateResponse = {
  id: '00000000-0000-4000-8000-000000010001',
  diaryDate: '2026-10-21',
  sourceText: '오늘 친구와 산책하며 즐겁게 이야기했다.',
  createdAt: '2026-10-21T12:00:00+09:00',
  generation: {
    id: '00000000-0000-4000-8000-000000010002',
    status: 'PENDING',
    title: null,
    imageUrl: null,
    imageUrlExpiresAt: null,
    completedAt: null,
  },
  usage: {
    usageDate: '2026-10-21',
    usedCount: 1,
    limitCount: 3,
    remainingCount: 2,
  },
};

beforeEach((): void => {
  localStorage.clear();
  sessionStorage.clear();
  mockGenerateDiary.mockReset().mockResolvedValue(acceptedResponse);
});
afterEach((): void => {
  localStorage.clear();
  sessionStorage.clear();
});

const renderAndSubmitDiary = async (): Promise<void> => {
  render(<DiaryWritePage />);
  const user = userEvent.setup();
  await user.type(screen.getByRole('textbox'), acceptedResponse.sourceText);
  await user.click(
    screen.getByRole('button', { name: DIARY_WRITE_COPY.createAction }),
  );
};

describe('일기 생성 접수 후 성공·실패 처리', (): void => {
  it('요청 접수 중에는 제출 버튼을 비활성화한다', async (): Promise<void> => {
    mockGenerateDiary.mockImplementationOnce(
      (): Promise<DiaryGenerateResponse> => new Promise((): void => {}),
    );
    await renderAndSubmitDiary();

    expect(
      screen.getByRole('button', { name: DIARY_WRITE_COPY.createAction }),
    ).toBeDisabled();
    expect(mockGenerateDiary).toHaveBeenCalledTimes(1);
  });

  it('요청 접수에 성공하면 일기 ID가 아닌 generation.id를 저장한다', async (): Promise<void> => {
    await renderAndSubmitDiary();

    await waitFor((): void => {
      expect(localStorage.getItem(DIARY_GENERATION_ID_KEY)).toBe(
        acceptedResponse.generation.id,
      );
    });
    expect(sessionStorage.getItem(DIARY_CONTENT_SESSION_KEY)).toBe(
      acceptedResponse.sourceText,
    );
    expect(mockNavigate).toHaveBeenCalledWith(-1);
    expect(mockAddToast).not.toHaveBeenCalled();
    expect(mockGenerateDiary).toHaveBeenCalledWith({
      diaryDate: expect.any(String),
      sourceText: acceptedResponse.sourceText,
      idempotencyKey: expect.any(String),
    });
  });

  it('요청 접수에 실패하면 토스트를 한 번 띄우고 작업 ID 저장과 이동을 하지 않는다', async (): Promise<void> => {
    const error = new Error('생성 접수 실패');
    mockGenerateDiary.mockRejectedValueOnce(error);
    await renderAndSubmitDiary();

    await waitFor((): void => {
      expect(mockCaptureError).toHaveBeenCalledWith(error, {
        feature: 'diary',
        operation: 'create',
      });
    });
    expect(localStorage.getItem(DIARY_GENERATION_ID_KEY)).toBeNull();
    expect(sessionStorage.getItem(DIARY_CONTENT_SESSION_KEY)).toBe(
      acceptedResponse.sourceText,
    );
    expect(mockAddToast).toHaveBeenCalledWith(
      { type: 'error', message: error.message },
      2000,
    );
    expect(mockNavigate).not.toHaveBeenCalled();

    const user = userEvent.setup();
    await user.type(screen.getByRole('textbox'), ' 내용을 수정했다.');
    expect(mockAddToast).toHaveBeenCalledTimes(1);
  });

  it('생성 횟수 초과도 토스트로 안내하되 예상된 오류는 추적하지 않는다', async (): Promise<void> => {
    const error = new RequestError({
      type: 'about:blank',
      title: 'Daily generation limit exceeded',
      status: 429,
      detail: '오늘 생성 가능한 횟수를 모두 사용했어요.',
      instance: '/api/v1/diaries',
      code: 'DAILY_GENERATION_LIMIT_EXCEEDED',
    });
    mockGenerateDiary.mockRejectedValueOnce(error);
    await renderAndSubmitDiary();

    await waitFor((): void => {
      expect(mockAddToast).toHaveBeenCalledWith(
        { type: 'error', message: error.message },
        2000,
      );
    });
    expect(mockAddToast).toHaveBeenCalledTimes(1);
    expect(mockCaptureError).not.toHaveBeenCalled();
  });
});
