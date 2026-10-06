import { css } from '@emotion/react';
import { useEffect, useRef, useState, type ReactElement } from 'react';
import ActionButton from '../../shared/ActionButton';
import type { ApiRequest } from '../../shared/api';
import { ERROR_MESSAGES } from '../../shared/errorMessage';
import { useAnalytics } from '../../posthog/useAnalytics';
import { useErrorTracking } from '../../posthog/useErrorTracking';
import copyIcon from '../../assets/icons/content-copy.svg';
import checkIcon from '../../assets/icons/check.svg';
import { DIARY_DETAIL_COPY } from './copy';

interface DiaryImageCopyButtonProps {
  readonly diaryId: string;
  readonly imageUrl: string;
}

const DiaryImageCopyButton = ({
  diaryId,
  imageUrl,
}: DiaryImageCopyButtonProps): ReactElement => {
  const [copyRequest, setCopyRequest] = useState<ApiRequest<void>>({
    status: 'idle',
  });
  const { track } = useAnalytics();
  const { captureError } = useErrorTracking();
  const isMounted = useRef(true);

  useEffect(() => {
    isMounted.current = true;
    return () => {
      isMounted.current = false;
    };
  }, []);

  const handleImageCopy = async (): Promise<void> => {
    if (copyRequest.status === 'loading' || copyRequest.status === 'success') {
      return;
    }

    if (
      typeof navigator.clipboard?.write !== 'function' ||
      typeof ClipboardItem === 'undefined' ||
      ClipboardItem.supports?.('image/png') === false
    ) {
      alert(ERROR_MESSAGES.DIARY_IMAGE_COPY_UNSUPPORTED);
      return;
    }

    setCopyRequest({ status: 'loading' });

    try {
      const pngPromise = createPngBlob(imageUrl);

      // 권한이 먼저 거부돼도 진행 중인 이미지 변환의 실패를 처리한다.
      void pngPromise.catch(() => undefined);

      // Safari의 클릭 권한이 유지되도록 변환 완료 전에 write를 호출한다.
      await navigator.clipboard.write([
        new ClipboardItem({ 'image/png': pngPromise }),
      ]);
    } catch (error: unknown) {
      const copyError =
        error instanceof Error
          ? error
          : new Error(ERROR_MESSAGES.DIARY_IMAGE_COPY_FAILED);

      setCopyRequest({ status: 'error', error: copyError });
      captureError(copyError, {
        feature: 'diary_image',
        operation: 'copy',
        diary_id: diaryId,
        image_role: 'original',
      });
      if (isMounted.current) {
        // 복사가 성공하기 전 다른 화면으로 이동했을 때 실패 메시지가 뜨지 않도록 한다.
        alert(ERROR_MESSAGES.DIARY_IMAGE_COPY_FAILED);
      }
      return;
    }

    setCopyRequest({ status: 'success', data: undefined });
    window.setTimeout((): void => {
      setCopyRequest({ status: 'idle' });
    }, 2_000);
    track('diary_image_copied', { diary_id: diaryId });
  };

  return (
    <ActionButton
      icon={
        copyRequest.status === 'success' ? (
          <span css={checkIconStyle} />
        ) : (
          <span css={copyIconStyle} />
        )
      }
      label={
        copyRequest.status === 'success'
          ? DIARY_DETAIL_COPY.copySuccess
          : DIARY_DETAIL_COPY.copyAction
      }
      variant="primary"
      onClick={handleImageCopy}
    />
  );
};

export default DiaryImageCopyButton;

const checkIconStyle = css`
  display: block;
  width: 24px;
  height: 24px;
  background-color: currentColor;
  mask: url(${checkIcon}) center / contain no-repeat;
`;

const copyIconStyle = css`
  display: block;
  width: 24px;
  height: 24px;
  background-color: currentColor;
  mask: url(${copyIcon}) center / contain no-repeat;
`;

const createPngBlob = async (imageUrl: string): Promise<Blob> => {
  const response = await fetch(imageUrl, { cache: 'no-store' });

  if (!response.ok) {
    throw new Error(`Image copy request failed (${response.status})`);
  }

  const sourceBlob = await response.blob();
  const objectUrl = URL.createObjectURL(sourceBlob);
  const image = new Image();
  const canvas = document.createElement('canvas');

  try {
    image.src = objectUrl;
    await image.decode();

    canvas.width = image.naturalWidth;
    canvas.height = image.naturalHeight;
    const context = canvas.getContext('2d');

    if (!context) {
      throw new Error(ERROR_MESSAGES.DIARY_IMAGE_COPY_FAILED);
    }

    context.drawImage(image, 0, 0);

    return await new Promise<Blob>((resolve, reject): void => {
      canvas.toBlob((blob): void => {
        if (blob) {
          resolve(blob);
        } else {
          reject(new Error(ERROR_MESSAGES.DIARY_IMAGE_COPY_FAILED));
        }
      }, 'image/png');
    });
  } finally {
    URL.revokeObjectURL(objectUrl);
    canvas.width = 0;
    canvas.height = 0;
  }
};
