import { ERROR_MESSAGES } from '../../shared/errorMessage';
import ActionButton from '../../shared/ActionButton';
import downloadIcon from '../../assets/icons/download.svg';
import { useState } from 'react';
import type { ApiRequest } from '../../shared/api';
import { useAnalytics } from '../../posthog/useAnalytics';

const DiaryImageDownloadButton = ({
  imageUrl,
  diaryDate,
  diaryTitle,
}: {
  imageUrl: string;
  diaryDate: string;
  diaryTitle: string;
}) => {
  const [downloadRequest, setDownloadRequest] = useState<ApiRequest<void>>({
    status: 'idle',
  });
  const { track } = useAnalytics();
  const handleImageDownload = async () => {
    setDownloadRequest({
      status: 'loading',
    });

    try {
      const response = await fetch(imageUrl, { cache: 'no-store' });

      if (!response.ok) {
        throw new Error(ERROR_MESSAGES.DIARY_IMAGE_SAVE_FAILED);
      }

      const blob = await response.blob();
      const downloadUrl = URL.createObjectURL(blob);
      const anchor = document.createElement('a');
      const safeTitle = diaryTitle
        .replace(/[<>:"/\\|?*]/g, '_')
        .trim()
        .replace(/\.+$/, '');
      // 제목 최대 80 bytes + 접두사·확장자 25 bytes로 Android 파일명 제한을 피한다.
      const shortTitle = Array.from(safeTitle).slice(0, 20).join('');

      anchor.href = downloadUrl;
      anchor.download = `하루들_${diaryDate}_${shortTitle}.png`;
      anchor.click();

      URL.revokeObjectURL(downloadUrl);

      track('diary_image_downloaded');
      setDownloadRequest({ status: 'success', data: undefined });
    } catch (error) {
      if (error instanceof Error) {
        setDownloadRequest({
          status: 'error',
          error: error,
        });
        alert(error.message);
      }
    }
  };

  return (
    <ActionButton
      icon={<img src={downloadIcon} alt="저장 아이콘" />}
      label="이미지 저장"
      variant="secondary"
      onClick={handleImageDownload}
      disabled={downloadRequest.status === 'loading'}
    />
  );
};

export default DiaryImageDownloadButton;
