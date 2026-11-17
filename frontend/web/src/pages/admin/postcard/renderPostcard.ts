import { composePostcard, type RasterImage } from './composePostcard';

/** 이미지를 불러온다. S3 서명 주소도 Canvas에서 읽을 수 있도록 CORS로 요청한다. */
const loadImage = (url: string): Promise<HTMLImageElement> =>
  new Promise((resolve, reject): void => {
    const image = new Image();
    image.crossOrigin = 'anonymous';
    image.onload = (): void => resolve(image);
    image.onerror = (): void => reject(new PostcardImageLoadError());
    image.src = url;
  });

const readPixels = (image: HTMLImageElement): RasterImage => {
  const canvas = document.createElement('canvas');
  canvas.width = image.naturalWidth;
  canvas.height = image.naturalHeight;
  const context = canvas.getContext('2d', { willReadFrequently: true });
  if (!context) throw new PostcardImageLoadError();
  context.drawImage(image, 0, 0);
  return context.getImageData(0, 0, canvas.width, canvas.height);
};

const toPngBlob = (postcard: RasterImage): Promise<Blob> => {
  const canvas = document.createElement('canvas');
  canvas.width = postcard.width;
  canvas.height = postcard.height;
  const context = canvas.getContext('2d');
  if (!context) return Promise.reject(new PostcardImageLoadError());
  context.putImageData(
    new ImageData(
      new Uint8ClampedArray(postcard.data),
      postcard.width,
      postcard.height,
    ),
    0,
    0,
  );
  return new Promise((resolve, reject): void => {
    canvas.toBlob((blob): void => {
      if (blob) resolve(blob);
      else reject(new PostcardImageLoadError());
    }, 'image/png');
  });
};

export class PostcardImageLoadError extends Error {
  constructor() {
    super('엽서에 쓸 이미지를 불러오지 못했습니다.');
    this.name = 'PostcardImageLoadError';
  }
}

/** 프레임과 만화 이미지를 받아 관리자 브라우저에서 엽서 PNG를 만든다. */
export const renderPostcard = async (
  frameUrl: string,
  comicUrl: string,
): Promise<Blob> => {
  const [frame, comic] = await Promise.all([
    loadImage(frameUrl),
    loadImage(comicUrl),
  ]);
  return toPngBlob(composePostcard(readPixels(frame), readPixels(comic)));
};
