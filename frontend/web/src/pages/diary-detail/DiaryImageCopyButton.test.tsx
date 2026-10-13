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
import DiaryImageCopyButton from './DiaryImageCopyButton';
import { DIARY_DETAIL_COPY } from './copy';
import { ERROR_MESSAGES } from '../../shared/errorMessage';

const mockClipboardWrite = jest.fn<(items: ClipboardItem[]) => Promise<void>>();
const mockFetch = jest.fn<typeof fetch>();
const mockTrack = jest.fn<(...args: unknown[]) => void>();
const mockCaptureError = jest.fn<(...args: unknown[]) => void>();

jest.mock('../../assets/icons/content-copy.svg', () => 'content-copy.svg');
jest.mock('../../assets/icons/check.svg', () => 'check.svg');
jest.mock('../../posthog/useAnalytics', () => ({
  useAnalytics: () => ({ track: mockTrack }),
}));
jest.mock('../../posthog/useErrorTracking', () => ({
  useErrorTracking: () => ({ captureError: mockCaptureError }),
}));

const fetchDescriptor = Object.getOwnPropertyDescriptor(window, 'fetch');
const clipboardDescriptor = Object.getOwnPropertyDescriptor(
  navigator,
  'clipboard',
);
const clipboardItemDescriptor = Object.getOwnPropertyDescriptor(
  window,
  'ClipboardItem',
);

describe('DiaryImageCopyButton', () => {
  beforeEach((): void => {
    jest.useFakeTimers();
    mockClipboardWrite.mockReset();
    mockFetch.mockReset();
    mockFetch.mockImplementation(() => new Promise<Response>(() => undefined));
    Object.defineProperty(window, 'fetch', {
      configurable: true,
      value: mockFetch,
    });
    mockTrack.mockReset();
    mockCaptureError.mockReset();
    jest.spyOn(window, 'alert').mockImplementation((): void => undefined);
    Object.defineProperty(navigator, 'clipboard', {
      configurable: true,
      value: { write: mockClipboardWrite },
    });
    Object.defineProperty(window, 'ClipboardItem', {
      configurable: true,
      value: class {
        static supports = (): boolean => true;
      },
    });
  });

  afterEach((): void => {
    cleanup();
    jest.useRealTimers();
    jest.restoreAllMocks();
    if (fetchDescriptor) {
      Object.defineProperty(window, 'fetch', fetchDescriptor);
    } else {
      Reflect.deleteProperty(window, 'fetch');
    }
    if (clipboardDescriptor) {
      Object.defineProperty(navigator, 'clipboard', clipboardDescriptor);
    } else {
      Reflect.deleteProperty(navigator, 'clipboard');
    }
    if (clipboardItemDescriptor) {
      Object.defineProperty(window, 'ClipboardItem', clipboardItemDescriptor);
    } else {
      Reflect.deleteProperty(window, 'ClipboardItem');
    }
  });

  it('복사 중에는 기본 모양을 유지하고, 성공 후 2초 동안만 체크 아이콘과 복사됨을 표시한다', async (): Promise<void> => {
    let completeCopy = (): void => undefined;
    mockClipboardWrite.mockReturnValue(
      new Promise<void>((resolve): void => {
        completeCopy = resolve;
      }),
    );
    render(<DiaryImageCopyButton diaryId="diary-1" imageUrl="/comic.webp" />);

    fireEvent.click(
      screen.getByRole('button', { name: DIARY_DETAIL_COPY.copyAction }),
    );
    const copyingButton = screen.getByRole('button', {
      name: DIARY_DETAIL_COPY.copyAction,
    });
    expect(copyingButton).toBeEnabled();
    fireEvent.click(copyingButton);
    expect(mockClipboardWrite).toHaveBeenCalledTimes(1);
    expect(mockFetch).toHaveBeenCalledWith('/comic.webp', {
      cache: 'no-store',
    });
    expect(screen.queryByRole('status')).not.toBeInTheDocument();
    expect(mockTrack).not.toHaveBeenCalled();

    await act(async (): Promise<void> => completeCopy());

    expect(mockTrack).toHaveBeenCalledWith('diary_image_copied', {
      diary_id: 'diary-1',
    });
    const copiedButton = screen.getByRole('button', {
      name: DIARY_DETAIL_COPY.copySuccess,
    });
    expect(copiedButton.querySelector('span > span')).toHaveStyle({
      mask: 'url(check.svg) center/contain no-repeat',
    });
    act((): void => jest.advanceTimersByTime(1_999));
    expect(copiedButton).toHaveTextContent(DIARY_DETAIL_COPY.copySuccess);
    act((): void => jest.advanceTimersByTime(1));
    const restoredButton = screen.getByRole('button', {
      name: DIARY_DETAIL_COPY.copyAction,
    });
    expect(restoredButton.querySelector('span > span')).toHaveStyle({
      mask: 'url(content-copy.svg) center/contain no-repeat',
    });
  });

  it('성공 표시 중에는 비활성화 없이 재복사를 막고 2초가 지나면 다시 복사할 수 있다', async (): Promise<void> => {
    mockClipboardWrite.mockResolvedValue(undefined);
    render(<DiaryImageCopyButton diaryId="diary-1" imageUrl="/comic.webp" />);

    await act(async (): Promise<void> => {
      fireEvent.click(screen.getByRole('button'));
    });
    act((): void => jest.advanceTimersByTime(1_500));
    expect(screen.getByRole('button')).toBeEnabled();
    await act(async (): Promise<void> => {
      fireEvent.click(screen.getByRole('button'));
    });
    expect(mockClipboardWrite).toHaveBeenCalledTimes(1);
    expect(mockTrack).toHaveBeenCalledTimes(1);

    act((): void => jest.advanceTimersByTime(500));
    expect(screen.getByRole('button')).toHaveTextContent(
      DIARY_DETAIL_COPY.copyAction,
    );
    await act(async (): Promise<void> => {
      fireEvent.click(screen.getByRole('button'));
    });
    expect(mockClipboardWrite).toHaveBeenCalledTimes(2);
    expect(screen.getByRole('button')).toHaveTextContent(
      DIARY_DETAIL_COPY.copySuccess,
    );
  });

  it('권한이 거부되면 성공 안내 없이 오류를 알리고 다시 복사할 수 있다', async (): Promise<void> => {
    const error = new DOMException('Permission denied', 'NotAllowedError');
    mockClipboardWrite.mockRejectedValueOnce(error);
    mockClipboardWrite.mockResolvedValueOnce(undefined);
    render(<DiaryImageCopyButton diaryId="diary-1" imageUrl="/comic.webp" />);

    await act(async (): Promise<void> => {
      fireEvent.click(
        screen.getByRole('button', { name: DIARY_DETAIL_COPY.copyAction }),
      );
    });

    expect(window.alert).toHaveBeenCalledWith(
      ERROR_MESSAGES.DIARY_IMAGE_COPY_FAILED,
    );
    expect(mockCaptureError).toHaveBeenCalledWith(error, {
      feature: 'diary_image',
      operation: 'copy',
      diary_id: 'diary-1',
      image_role: 'original',
    });
    expect(mockTrack).not.toHaveBeenCalled();
    expect(screen.queryByRole('status')).not.toBeInTheDocument();

    await act(async (): Promise<void> => {
      fireEvent.click(
        screen.getByRole('button', { name: DIARY_DETAIL_COPY.copyAction }),
      );
    });
    expect(mockTrack).toHaveBeenCalledTimes(1);
  });

  it('클립보드 API가 없으면 변환을 시작하지 않고 저장 기능을 안내한다', (): void => {
    Object.defineProperty(navigator, 'clipboard', {
      configurable: true,
      value: undefined,
    });
    render(<DiaryImageCopyButton diaryId="diary-1" imageUrl="/comic.webp" />);

    fireEvent.click(
      screen.getByRole('button', { name: DIARY_DETAIL_COPY.copyAction }),
    );

    expect(window.alert).toHaveBeenCalledWith(
      ERROR_MESSAGES.DIARY_IMAGE_COPY_UNSUPPORTED,
    );
    expect(mockClipboardWrite).not.toHaveBeenCalled();
    expect(mockFetch).not.toHaveBeenCalled();
    expect(screen.queryByRole('status')).not.toBeInTheDocument();
  });
});
