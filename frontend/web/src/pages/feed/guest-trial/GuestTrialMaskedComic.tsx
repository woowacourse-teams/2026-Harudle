import { css, type SerializedStyles } from '@emotion/react';
import {
  useEffect,
  useEffectEvent,
  useRef,
  useState,
  type SyntheticEvent,
} from 'react';
import { theme } from '../../../styles/theme';
import { GUEST_TRIAL_COPY } from './copy';

// 한 컷을 가로·세로 몇 점으로 줄였다가 부드럽게 키울지 정한다. 작을수록 더 흐려진다.
// 장면의 분위기와 윤곽은 보이되 표정·글자는 알아보기 어려운 정도로 둔다.
const BLUR_SAMPLES_PER_PANEL = 40;

/** 2x2 네컷에서 가릴 칸(기·결). x·y는 0부터 시작하는 열·행이다. */
const MASKED_PANELS = [
  { x: 0, y: 0 },
  { x: 1, y: 1 },
] as const;

type MaskedPanel = (typeof MASKED_PANELS)[number];

interface SourceRegion {
  readonly source: CanvasImageSource;
  readonly x: number;
  readonly y: number;
  readonly width: number;
  readonly height: number;
}

const createCanvas = (
  width: number,
  height: number,
): { canvas: HTMLCanvasElement; context: CanvasRenderingContext2D } | null => {
  const canvas = document.createElement('canvas');
  canvas.width = width;
  canvas.height = height;
  const context = canvas.getContext('2d');
  if (!context) return null;

  context.imageSmoothingEnabled = true;
  context.imageSmoothingQuality = 'high';
  return { canvas, context };
};

/**
 * 한 컷을 samples 크기로 줄인다. 한 번에 크게 줄이면 브라우저가 일부 점만 골라
 * 선이 많은 컷은 흑백으로 거칠어지므로, 절반씩 나눠 줄여 색을 고르게 평균낸다.
 * 픽셀을 직접 읽지 않아 CORS가 없는 이미지 주소에서도 동작한다.
 */
const shrinkPanel = (
  region: SourceRegion,
  samples: number,
): HTMLCanvasElement | null => {
  let current = region;

  while (current.width / 2 >= samples && current.height / 2 >= samples) {
    const half = createCanvas(
      Math.round(current.width / 2),
      Math.round(current.height / 2),
    );
    if (!half) return null;

    half.context.drawImage(
      current.source,
      current.x,
      current.y,
      current.width,
      current.height,
      0,
      0,
      half.canvas.width,
      half.canvas.height,
    );
    current = {
      source: half.canvas,
      x: 0,
      y: 0,
      width: half.canvas.width,
      height: half.canvas.height,
    };
  }

  const result = createCanvas(samples, samples);
  if (!result) return null;

  result.context.drawImage(
    current.source,
    current.x,
    current.y,
    current.width,
    current.height,
    0,
    0,
    samples,
    samples,
  );
  return result.canvas;
};

/**
 * 작게 줄인 컷을 두 배씩 나눠 키운다. 한 번에 키우면 보간 격자가 드러나므로
 * 단계마다 부드럽게 이어 붙여 블러처럼 보이게 한다.
 */
const enlargePanel = (
  small: HTMLCanvasElement,
  width: number,
  height: number,
): HTMLCanvasElement | null => {
  let current = small;

  while (current.width * 2 < width && current.height * 2 < height) {
    const next = createCanvas(current.width * 2, current.height * 2);
    if (!next) return null;

    next.context.drawImage(
      current,
      0,
      0,
      next.canvas.width,
      next.canvas.height,
    );
    current = next.canvas;
  }
  return current;
};

/**
 * 원본을 그린 뒤 1·4컷(기·결)만 저해상도로 줄였다가 부드럽게 키워 흐리게 만든다.
 * 화면과 저장 결과 모두 흐려진 그림만 남도록 원본 이미지를 DOM에 두지 않는다.
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
    const small = shrinkPanel(
      {
        source: image,
        x: left,
        y: top,
        width: panelWidth,
        height: panelHeight,
      },
      BLUR_SAMPLES_PER_PANEL,
    );
    const blurred = small && enlargePanel(small, panelWidth, panelHeight);
    if (!blurred) return false;

    context.imageSmoothingEnabled = true;
    context.imageSmoothingQuality = 'high';
    context.drawImage(
      blurred,
      0,
      0,
      blurred.width,
      blurred.height,
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
      {MASKED_PANELS.map((panel) => (
        <span
          key={`${panel.x}-${panel.y}`}
          aria-hidden="true"
          css={[frostStyle, frostPositionStyle(panel)]}
        />
      ))}
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

/*
 * canvas에서 줄였다 키운 컷은 확대 격자가 남으므로, 같은 영역에 옅은 유리막을 덮어
 * 격자를 지운다.
 */
const frostStyle = css`
  position: absolute;
  width: 50%;
  height: 50%;
  background: rgb(255 255 255 / 10%);
  -webkit-backdrop-filter: blur(4px);
  backdrop-filter: blur(4px);
  pointer-events: none;
`;

const frostPositionStyle = (panel: MaskedPanel): SerializedStyles => css`
  top: ${panel.y * 50}%;
  left: ${panel.x * 50}%;
`;
