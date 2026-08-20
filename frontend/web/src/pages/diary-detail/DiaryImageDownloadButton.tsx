import ActionButton from '../../shared/ActionButton';
import downloadIcon from '../../assets/icons/download.svg';
import { useState } from 'react';
import type { ApiRequest } from '../../shared/api';
import { useAnalytics } from '../../shared/useAnalytics';

const DiaryImageDownloadButton = ({ imageUrl }: { imageUrl: string }) => {
  const [downloadRequest, setDownloadRequest] = useState<ApiRequest<void>>({
    status: 'idle',
  });
  const { track } = useAnalytics();
  const handleImageDownload = async () => {
    setDownloadRequest({
      status: 'loading',
    });

    try {
      const response = await fetch(imageUrl);

      if (!response.ok) {
        throw new Error('이미지 저장에 실패했습니다.');
      }

      const blob = await response.blob();
      const downloadUrl = URL.createObjectURL(blob);
      const anchor = document.createElement('a');

      anchor.href = downloadUrl;
      anchor.download = 'harudle-diary.png';
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
