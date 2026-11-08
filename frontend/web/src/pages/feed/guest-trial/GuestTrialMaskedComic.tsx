import { css } from '@emotion/react';
import {
  useEffect,
  useEffectEvent,
  useRef,
  useState,
  type SyntheticEvent,
} from 'react';
import { theme } from '../../../styles/theme';
import { GUEST_TRIAL_COPY } from './copy';

// 한 컷을 가로·세로 몇 칸의 모자이크로 줄일지 정한다. 작을수록 블록이 커진다.
// 장면의 윤곽만 어렴풋이 보이도록 블록을 잘게 둔다.
const MOSAIC_BLOCKS_PER_PANEL = 18;

/** 2x2 네컷에서 가릴 칸(기·결). x·y는 0부터 시작하는 열·행이다. */
const MASKED_PANELS = [
  { x: 0, y: 0 },
  { x: 1, y: 1 },
] as const;

interface SourceRegion {
  readonly source: CanvasImageSource;
  readonly x: number;
  readonly y: number;
  readonly width: number;
  readonly height: number;
}

/**
 * 한 컷을 블록 수 크기로 줄인다. 한 번에 크게 줄이면 브라우저가 일부 점만 골라
 * 선이 많은 컷은 흑백으로 거칠어지므로, 절반씩 나눠 줄여 블록마다 색을 고르게 평균낸다.
 * 픽셀을 직접 읽지 않아 CORS가 없는 이미지 주소에서도 동작한다.
 */
const shrinkPanel = (
  region: SourceRegion,
  blocks: number,
): HTMLCanvasElement | null => {
  let current = region;

  while (current.width / 2 >= blocks && current.height / 2 >= blocks) {
    const half = document.createElement('canvas');
    half.width = Math.round(current.width / 2);
    half.height = Math.round(current.height / 2);
    const halfContext = half.getContext('2d');
    if (!halfContext) return null;

    halfContext.imageSmoothingEnabled = true;
    halfContext.imageSmoothingQuality = 'high';
    halfContext.drawImage(
      current.source,
      current.x,
      current.y,
      current.width,
      current.height,
      0,
      0,
      half.width,
      half.height,
    );
    current = {
      source: half,
      x: 0,
      y: 0,
      width: half.width,
      height: half.height,
    };
  }

  const result = document.createElement('canvas');
  result.width = blocks;
  result.height = blocks;
  const resultContext = result.getContext('2d');
  if (!resultContext) return null;

  resultContext.imageSmoothingEnabled = true;
  resultContext.imageSmoothingQuality = 'high';
  resultContext.drawImage(
    current.source,
    current.x,
    current.y,
    current.width,
    current.height,
    0,
    0,
    blocks,
    blocks,
  );
  return result;
};

/**
 * 원본을 그린 뒤 1·4컷(기·결)만 저해상도로 줄였다가 다시 키워 모자이크를 만든다.
 * 화면과 저장 결과 모두 모자이크된 그림만 남도록 원본 이미지를 DOM에 두지 않는다.
 */
export const drawMaskedComic = (
  canvas: HTMLCanvasElement,
  image: HTMLImageElement,
): boolean => {
  const width = image.naturalWidth;
  const height = image.naturalHeight;
  const context = canvas.getContext('2d');
  if (!context || width === 0 || height === 0) return false;

  canvas.width = width;
  canvas.height = height;
  context.drawImage(image, 0, 0, width, height);

  const panelWidth = width / 2;
  const panelHeight = height / 2;
  for (const panel of MASKED_PANELS) {
    const left = panel.x * panelWidth;
    const top = panel.y * panelHeight;
    const mosaic = shrinkPanel(
      {
        source: image,
        x: left,
        y: top,
        width: panelWidth,
        height: panelHeight,
      },
      MOSAIC_BLOCKS_PER_PANEL,
    );
    if (!mosaic) return false;

    // 블록 경계가 흐려지지 않도록 확대할 때는 보간하지 않는다.
    context.imageSmoothingEnabled = false;
    context.drawImage(
      mosaic,
      0,
      0,
      mosaic.width,
      mosaic.height,
      left,
      top,
      panelWidth,
      panelHeight,
    );
  }

  return true;
};

const preventSaving = (event: SyntheticEvent): void => {
  event.preventDefault();
};

interface GuestTrialMaskedComicProps {
  readonly imageUrl: string;
  readonly onLoad: () => void;
  readonly onError: () => void;
}

/** 로그인 전 결과. 우클릭·드래그·길게 누르기로 이미지를 저장하는 동작을 막는다. */
const GuestTrialMaskedComic = ({
  imageUrl,
  onLoad,
  onError,
}: GuestTrialMaskedComicProps) => {
  const canvasRef = useRef<HTMLCanvasElement>(null);
  const [isDrawn, setIsDrawn] = useState(false);
  const handleLoad = useEffectEvent(onLoad);
  const handleError = useEffectEvent(onError);

  useEffect((): (() => void) => {
    let isActive = true;
    const image = new Image();
    image.decoding = 'async';
    image.onload = (): void => {
      if (!isActive) return;
      const canvas = canvasRef.current;
      if (canvas && drawMaskedComic(canvas, image)) {
        setIsDrawn(true);
        handleLoad();
        return;
      }
      handleError();
    };
    image.onerror = (): void => {
      if (isActive) handleError();
    };
    image.src = imageUrl;

    return (): void => {
      isActive = false;
      image.onload = null;
      image.onerror = null;
    };
  }, [imageUrl]);

  return (
    <div
      css={frameStyle}
      role="img"
      aria-label={GUEST_TRIAL_COPY.maskedImageLabel}
      aria-busy={!isDrawn}
      onContextMenu={preventSaving}
      onDragStart={preventSaving}
    >
      <canvas ref={canvasRef} aria-hidden="true" css={canvasStyle} />
    </div>
  );
};

export default GuestTrialMaskedComic;

const frameStyle = css`
  position: relative;
  width: 100%;
  max-width: 300px;
  aspect-ratio: 1;
  margin: 16px auto 0;
  overflow: hidden;
  border: 1px solid ${theme.colors.stroke.outline};
  border-radius: 12px;
  background: ${theme.colors.background.neutralWeak};
  user-select: none;
  -webkit-user-select: none;
  -webkit-touch-callout: none;
`;

const canvasStyle = css`
  display: block;
  width: 100%;
  height: 100%;
  /* 우클릭 대상이 canvas가 아니게 해 브라우저의 이미지 저장 메뉴가 뜨지 않게 한다. */
  pointer-events: none;
`;
