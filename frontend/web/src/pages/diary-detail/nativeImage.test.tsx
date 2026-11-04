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
import DiaryImageDownloadButton from './DiaryImageDownloadButton';
import { DIARY_DETAIL_COPY, NATIVE_IMAGE_COPY } from './copy';
import { requestNativeImage } from './nativeImage';
import { isRecord } from '../../shared/utils';

const mockPostMessage = jest.fn<(data: string) => void>();
const mockTrack = jest.fn();
const mockAddToast = jest.fn();

jest.mock('../../posthog/useAnalytics', () => ({
  useAnalytics: () => ({ track: mockTrack }),
}));
jest.mock('../../posthog/useErrorTracking', () => ({
  useErrorTracking: () => ({ captureError: jest.fn() }),
}));
jest.mock('../../shared/ToastContext', () => ({
  useToastContext: () => ({ addToast: mockAddToast }),
}));
jest.mock('../../assets/icons/content-copy.svg', () => 'copy.svg');
jest.mock('../../assets/icons/check.svg', () => 'check.svg');
jest.mock('../../assets/icons/download.svg', () => 'download.svg');

const respond = (payload: unknown, id?: string, requestIndex = 0): void => {
  const request: unknown = JSON.parse(
    mockPostMessage.mock.calls[requestIndex][0],
  );
  if (!isRecord(request)) throw new Error('Invalid request');
  window.dispatchEvent(
    new CustomEvent('harudle:message', {
      detail: { id: id ?? request.id, type: 'image.result', payload },
    }),
  );
};

describe('앱 이미지 저장·복사', () => {
  beforeEach((): void => {
    jest.useFakeTimers();
    mockPostMessage.mockReset();
    mockTrack.mockReset();
    mockAddToast.mockReset();
    Object.defineProperties(window, {
      isHarudleApp: { configurable: true, value: true },
      ReactNativeWebView: {
        configurable: true,
        value: { postMessage: mockPostMessage },
      },
    });
  });

  afterEach((): void => {
    cleanup();
    jest.clearAllTimers();
    jest.useRealTimers();
    Reflect.deleteProperty(window, 'isHarudleApp');
    Reflect.deleteProperty(window, 'ReactNativeWebView');
  });

  it.each(['image.copy', 'image.save'] as const)(
    '%s는 앱 성공 응답을 받은 뒤에만 완료 표시와 분석 이벤트를 기록한다',
    async (type): Promise<void> => {
      const props = {
        diaryId: 'diary-1',
        imageUrl: 'https://images.example/comic.webp',
      };
      render(
        type === 'image.copy' ? (
          <DiaryImageCopyButton {...props} />
        ) : (
          <DiaryImageDownloadButton
            {...props}
            diaryDate="2026-11-03"
            diaryTitle="오늘"
          />
        ),
      );

      fireEvent.click(screen.getByRole('button'));
      fireEvent.click(screen.getByRole('button'));
      expect(mockPostMessage).toHaveBeenCalledTimes(1);
      expect(JSON.parse(mockPostMessage.mock.calls[0][0])).toEqual({
        id: expect.any(String),
        type,
        payload: { imageUrl: props.imageUrl },
      });
      expect(mockTrack).not.toHaveBeenCalled();
      expect(mockAddToast).not.toHaveBeenCalled();

      await act(async (): Promise<void> => respond({ status: 'success' }));

      if (type === 'image.copy') {
        expect(screen.getByRole('button')).toHaveTextContent(
          DIARY_DETAIL_COPY.copySuccess,
        );
        expect(mockTrack).toHaveBeenCalledWith('diary_image_copied', {
          diary_id: 'diary-1',
        });
      } else {
        expect(mockAddToast).toHaveBeenCalledWith(
          { type: 'success', message: NATIVE_IMAGE_COPY.saveSuccess },
          2000,
        );
        expect(mockTrack).toHaveBeenCalledWith('diary_image_downloaded');
        expect(screen.getByRole('button')).toBeEnabled();
      }
    },
  );

  it('권한 거절은 성공으로 기록하지 않고 오류 안내 후 재시도를 허용한다', async (): Promise<void> => {
    render(
      <DiaryImageDownloadButton
        diaryId="1"
        imageUrl="https://images.example/comic.webp"
        diaryDate="2026-11-03"
        diaryTitle="오늘"
      />,
    );
    fireEvent.click(screen.getByRole('button'));
    await act(async (): Promise<void> =>
      respond({ status: 'error', message: '사진 저장 권한이 필요해요.' }),
    );
    expect(mockTrack).not.toHaveBeenCalled();
    expect(mockAddToast).toHaveBeenCalledWith(
      { type: 'error', message: '사진 저장 권한이 필요해요.' },
      2000,
    );
    expect(screen.getByRole('button')).toBeEnabled();
    fireEvent.click(screen.getByRole('button'));
    await act(async (): Promise<void> =>
      respond({ status: 'success' }, undefined, 1),
    );
    expect(mockTrack).toHaveBeenCalledTimes(1);
  });

  it('다른 요청의 응답과 잘못된 응답은 무시하고 제한 시간이 지나면 대기를 끝낸다', async (): Promise<void> => {
    const result = expect(
      requestNativeImage('image.copy', 'https://images.example/comic.webp'),
    ).rejects.toThrow(NATIVE_IMAGE_COPY.timeout);
    respond({ status: 'success' }, 'another-request');
    respond({ status: 'error', message: 123 });
    jest.advanceTimersByTime(60_000);
    await result;
    expect(jest.getTimerCount()).toBe(0);
  });

  it('앱 통신 객체가 없으면 대기하지 않고 실패를 반환한다', async (): Promise<void> => {
    Reflect.deleteProperty(window, 'ReactNativeWebView');
    await expect(
      requestNativeImage('image.save', 'https://images.example/comic.webp'),
    ).rejects.toThrow(NATIVE_IMAGE_COPY.timeout);
    expect(mockPostMessage).not.toHaveBeenCalled();
    expect(jest.getTimerCount()).toBe(0);
  });
});
