const extensionByMediaType: Record<string, string> = {
  'image/gif': 'gif',
  'image/jpeg': 'jpg',
  'image/png': 'png',
  'image/webp': 'webp',
};

export const downloadImage = async (
  imageUrl: string,
  filename: string,
): Promise<void> => {
  const response = await fetch(imageUrl, { cache: 'no-store' });

  if (!response.ok) {
    throw new Error('이미지를 저장하지 못했습니다.');
  }

  const imageBlob = await response.blob();
  const extension = extensionByMediaType[imageBlob.type];

  if (!extension) {
    throw new Error('지원하지 않는 이미지 형식입니다.');
  }

  const objectUrl = URL.createObjectURL(imageBlob);
  const downloadLink = document.createElement('a');
  downloadLink.href = objectUrl;
  downloadLink.download = `${filename}.${extension}`;
  document.body.append(downloadLink);
  downloadLink.click();
  downloadLink.remove();

  window.setTimeout(() => URL.revokeObjectURL(objectUrl), 1_000);
};
