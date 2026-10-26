import {
  afterEach,
  beforeEach,
  describe,
  expect,
  it,
  jest,
} from '@jest/globals';
import {
  act,
  cleanup,
  fireEvent,
  render,
  screen,
} from '@testing-library/react';
import { getDiaryGenerationStatus } from '../domain/diary/diaryGenerate';
import {
  getGenerationId,
  setGenerationId,
} from '../domain/diary/diaryGenerationStore';
import DiaryGenerationNotice from './DiaryGenerationNotice';
import { ToastProvider } from './ToastContext';
import { RequestError } from './api';

jest.mock('../domain/diary/diaryGenerate', () => ({
  getDiaryGenerationStatus: jest.fn(),
}));

const mockGetStatus = jest.mocked(getDiaryGenerationStatus);
const mockNavigate = jest.fn();
const generationId = '00000000-0000-4000-8000-000000010001';

jest.mock('react-router', () => ({
  useNavigate: () => mockNavigate,
}));
jest.mock('../assets/icons/toast-success.svg', () => 'toast-success.svg');
jest.mock('../assets/icons/toast-error.svg', () => 'toast-error.svg');
jest.mock('../assets/icons/toast-loading.svg', () => 'toast-loading.svg');

beforeEach((): void => {
  jest.useFakeTimers();
  setGenerationId(generationId);
});

afterEach((): void => {
  cleanup();
  localStorage.clear();
  jest.useRealTimers();
  mockGetStatus.mockReset();
});

describe('생성 실패 안내', (): void => {
  it.each([
    {
      action: '이전 화면으로 돌아가 생성 결과를 확인해 주세요.',
      dismissed: false,
    },
    {
      action: '이전 화면으로 돌아가 생성 결과를 확인해 주세요.',
      dismissed: true,
    },
    { action: undefined, dismissed: false },
    { action: null, dismissed: false },
    { action: '   ', dismissed: false },
    { action: 123, dismissed: false },
  ])(
    '조회 오류의 action=$action, 알림 삭제=$dismissed에 맞게 안내한다',
    async ({ action, dismissed }): Promise<void> => {
      const error = new RequestError({
        type: 'about:blank',
        title: 'Not Found',
        status: 404,
        detail: '생성 기록을 찾을 수 없어요.',
        instance: `/api/v1/diary-generations/${generationId}`,
        code: 'GENERATION_NOT_FOUND',
        action,
      });
      mockGetStatus.mockRejectedValue(error);
      render(
        <ToastProvider>
          <DiaryGenerationNotice />
        </ToastProvider>,
      );
      if (dismissed) {
        fireEvent.click(screen.getByRole('button', { name: '×' }));
      }

      await act(async (): Promise<void> => {
        await jest.advanceTimersByTimeAsync(5000);
      });

      expect(screen.getByText(error.message)).toBeInTheDocument();
      expect(
        screen.getByRole('button', { name: '다시 작성하기' }),
      ).toBeInTheDocument();
      expect(getGenerationId()).toBeNull();
      if (typeof action === 'string' && action.trim()) {
        expect(screen.queryByText('다시 시도해주세요')).not.toBeInTheDocument();
      } else {
        expect(screen.getByText('다시 시도해주세요')).toBeInTheDocument();
      }
    },
  );

  it.each([
    {
      action:
        '작성한 내용으로 생성할 수 없어요. 내용을 바꿔 다시 시도해 주세요.',
      message:
        '작성한 내용으로 생성할 수 없어요. 내용을 바꿔 다시 시도해 주세요.',
    },
    { action: undefined, message: '네컷만화 생성에 실패했어요.' },
    { action: null, message: '네컷만화 생성에 실패했어요.' },
    { action: '   ', message: '네컷만화 생성에 실패했어요.' },
    { action: 123, message: '네컷만화 생성에 실패했어요.' },
  ])(
    'action=$action일 때 안내와 기존 버튼을 표시한다',
    async ({ action, message }): Promise<void> => {
      mockGetStatus.mockResolvedValue({
        id: generationId,
        status: 'FAILED',
        diaryId: null,
        errorCode: 'AI_CONTENT_BLOCKED',
        action,
      });
      render(
        <ToastProvider>
          <DiaryGenerationNotice />
        </ToastProvider>,
      );

      await act(async (): Promise<void> => {
        await jest.advanceTimersByTimeAsync(5000);
      });

      expect(screen.getByText(message)).toBeInTheDocument();
      expect(
        screen.getByRole('button', { name: '다시 작성하기' }),
      ).toBeInTheDocument();
      expect(getGenerationId()).toBeNull();
      if (typeof action === 'string' && action.trim()) {
        expect(
          screen.queryByText('잠시 후 다시 만들어 주세요.'),
        ).not.toBeInTheDocument();
      } else {
        expect(
          screen.getByText('잠시 후 다시 만들어 주세요.'),
        ).toBeInTheDocument();
      }
    },
  );
});
