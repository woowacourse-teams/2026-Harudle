// 서비스에서 만든 2x2 네컷만화를 엽서 프레임의 네 칸에 옮겨 담는다.
// 브라우저 Canvas의 ImageData와 같은 모양의 픽셀 배열만 다뤄서, 서버 자원을 쓰지 않고 테스트하기 쉽게 둔다.
// 프레임 캐릭터가 칸에 걸친 부분은 프레임 픽셀을 그대로 남긴다.

export interface RasterImage {
  readonly width: number;
  readonly height: number;
  readonly data: Uint8ClampedArray;
}

/** 양 끝을 포함하는 픽셀 상자 */
export interface PixelBox {
  readonly x0: number;
  readonly y0: number;
  readonly x1: number;
  readonly y1: number;
}

interface FramePanel {
  readonly box: PixelBox;
  /** 만화로 채울 픽셀 위치(y * width + x) */
  readonly pixels: Uint32Array;
}

export class PostcardCompositionError extends Error {
  constructor(message: string) {
    super(message);
    this.name = 'PostcardCompositionError';
  }
}

// 프레임 네 칸의 대략적인 중심(가로·세로 비율). 프레임 디자인을 바꾸면 함께 확인한다.
const FRAME_PANEL_CENTERS = [
  [0.26, 0.4],
  [0.74, 0.4],
  [0.26, 0.72],
  [0.74, 0.72],
] as const;
// 칸 테두리의 부드러운 경계 픽셀까지 덮지 않도록 안쪽으로 줄이는 두께(프레임 가로 1333px 기준)
const FRAME_INNER_MARGIN_AT_1333 = 3;

const luminance = (data: Uint8ClampedArray, offset: number): number =>
  0.299 * data[offset] + 0.587 * data[offset + 1] + 0.114 * data[offset + 2];

/** 프레임 칸 안쪽의 흰 영역. 칸 중심에서 이어진 무채색 흰 픽셀만 모으므로 칸에 걸친 캐릭터는 빠진다. */
export const findFramePanels = (frame: RasterImage): FramePanel[] => {
  const { width, height, data } = frame;
  const isPaper = (index: number): boolean => {
    const offset = index * 4;
    const r = data[offset];
    const g = data[offset + 1];
    const b = data[offset + 2];
    return (
      luminance(data, offset) > 215 &&
      Math.abs(r - g) < 18 &&
      Math.abs(g - b) < 18
    );
  };
  const margin = Math.max(
    1,
    Math.round((FRAME_INNER_MARGIN_AT_1333 * width) / 1333),
  );

  return FRAME_PANEL_CENTERS.map(([u, v], panelIndex) => {
    const filled = new Uint8Array(width * height);
    const start = Math.round(v * height) * width + Math.round(u * width);
    if (!isPaper(start)) {
      throw new PostcardCompositionError(
        `프레임 ${panelIndex + 1}번 칸의 흰 영역을 찾지 못했습니다.`,
      );
    }
    filled[start] = 1;
    const stack = [start];
    let x0 = width;
    let y0 = height;
    let x1 = -1;
    let y1 = -1;
    while (stack.length > 0) {
      const index = stack.pop() as number;
      const x = index % width;
      const y = (index - x) / width;
      if (x < x0) x0 = x;
      if (x > x1) x1 = x;
      if (y < y0) y0 = y;
      if (y > y1) y1 = y;
      const neighbors = [
        x + 1 < width ? index + 1 : -1,
        x > 0 ? index - 1 : -1,
        y + 1 < height ? index + width : -1,
        y > 0 ? index - width : -1,
      ];
      for (const next of neighbors) {
        if (next >= 0 && filled[next] === 0 && isPaper(next)) {
          filled[next] = 1;
          stack.push(next);
        }
      }
    }
    if (x1 - x0 < width * 0.2 || y1 - y0 < height * 0.15) {
      throw new PostcardCompositionError(
        `프레임 ${panelIndex + 1}번 칸이 너무 작습니다.`,
      );
    }

    const pixels: number[] = [];
    for (let y = y0; y <= y1; y += 1) {
      for (let x = x0; x <= x1; x += 1) {
        if (isInsideBy(filled, width, height, x, y, margin))
          pixels.push(y * width + x);
      }
    }
    return { box: { x0, y0, x1, y1 }, pixels: Uint32Array.from(pixels) };
  });
};

/** (x, y)에서 상하좌우로 margin만큼 떨어진 곳까지 모두 채워져 있으면 안쪽으로 본다. */
const isInsideBy = (
  filled: Uint8Array,
  width: number,
  height: number,
  x: number,
  y: number,
  margin: number,
): boolean => {
  if (x < margin || y < margin || x >= width - margin || y >= height - margin)
    return false;
  const index = y * width + x;
  return (
    filled[index] === 1 &&
    filled[index - margin] === 1 &&
    filled[index + margin] === 1 &&
    filled[index - margin * width] === 1 &&
    filled[index + margin * width] === 1
  );
};

interface LineRun {
  readonly start: number;
  readonly end: number;
}

const center = (run: LineRun): number => (run.start + run.end) / 2;

/**
 * 서비스 만화의 2x2 칸 상자를 찾는다. 서비스는 바깥 테두리 1개, 세로·가로 구분선 1개씩으로 칸을 나눈다.
 * 바탕이 어두운 예전 그림체는 밝은 선을 구분선으로 본다. 바깥 테두리가 없으면 그림 가장자리를 쓴다.
 */
export const findComicPanels = (comic: RasterImage): PixelBox[] => {
  const { width, height, data } = comic;
  let total = 0;
  for (let offset = 0; offset < data.length; offset += 16)
    total += luminance(data, offset);
  const darkBackground = total / (data.length / 16) < 110;
  const ink = new Uint8Array(width * height);
  for (let index = 0; index < ink.length; index += 1) {
    const value = luminance(data, index * 4);
    ink[index] = (darkBackground ? value > 145 : value < 110) ? 1 : 0;
  }

  // 손으로 그린 듯 조금 흔들리는 선도 잡도록 앞뒤 2px 안에 선 픽셀이 있으면 그 줄에 선이 있다고 본다
  const columnRuns = findLineRuns(width, (x) =>
    lineCoverage(Math.round(height * 0.05), Math.round(height * 0.95), (y) =>
      hasInkNear(ink, width, x, y, true),
    ),
  );
  const middleX = pickNearest(columnRuns, width / 2, width * 0.4, width * 0.6);
  if (!middleX)
    throw new PostcardCompositionError('만화의 세로 구분선을 찾지 못했습니다.');
  const left = pickNearest(
    columnRuns,
    0,
    0,
    center(middleX) - width * 0.25,
  ) ?? { start: -1, end: -1 };
  const right = pickNearest(
    columnRuns,
    width,
    center(middleX) + width * 0.25,
    width,
  ) ?? {
    start: width,
    end: width,
  };

  const rowRuns = findLineRuns(height, (y) =>
    lineCoverage(left.end + 1, right.start - 1, (x) =>
      hasInkNear(ink, width, x, y, false),
    ),
  );
  const middleY = pickNearest(
    rowRuns,
    height * 0.47,
    height * 0.3,
    height * 0.65,
  );
  if (!middleY)
    throw new PostcardCompositionError('만화의 가로 구분선을 찾지 못했습니다.');
  const top = pickNearest(rowRuns, 0, 0, center(middleY) - height * 0.25) ?? {
    start: -1,
    end: -1,
  };
  const expectedBottom = 2 * center(middleY) - center(top);
  const bottom = pickNearest(
    rowRuns,
    expectedBottom,
    center(middleY) + height * 0.25,
    height,
  ) ?? {
    start: height,
    end: height,
  };

  const pad = Math.max(2, Math.round(width * 0.008));
  const columns = [
    [left.end + 1 + pad, middleX.start - 1 - pad],
    [middleX.end + 1 + pad, right.start - 1 - pad],
  ];
  const rows = [
    [top.end + 1 + pad, middleY.start - 1 - pad],
    [middleY.end + 1 + pad, bottom.start - 1 - pad],
  ];
  const boxes = rows.flatMap(([y0, y1]) =>
    columns.map(([x0, x1]) => ({ x0, y0, x1, y1 })),
  );

  const widths = boxes.map((box) => box.x1 - box.x0);
  const heights = boxes.map((box) => box.y1 - box.y0);
  const uneven = (values: number[]): boolean =>
    Math.min(...values) < Math.max(...values) * 0.85;
  if (
    Math.min(...widths, ...heights) < width * 0.2 ||
    uneven(widths) ||
    uneven(heights)
  ) {
    throw new PostcardCompositionError(
      '만화의 네 칸 크기가 고르지 않아 칸을 나누지 못했습니다.',
    );
  }
  return boxes;
};

const hasInkNear = (
  ink: Uint8Array,
  width: number,
  x: number,
  y: number,
  vertical: boolean,
): boolean => {
  for (let delta = -2; delta <= 2; delta += 1) {
    const nx = vertical ? x + delta : x;
    const ny = vertical ? y : y + delta;
    if (nx < 0 || nx >= width || ny < 0 || ny >= ink.length / width) continue;
    if (ink[ny * width + nx] === 1) return true;
  }
  return false;
};

const lineCoverage = (
  from: number,
  to: number,
  isLine: (position: number) => boolean,
): number => {
  let hits = 0;
  let samples = 0;
  for (let position = Math.max(0, from); position <= to; position += 2) {
    samples += 1;
    if (isLine(position)) hits += 1;
  }
  return samples === 0 ? 0 : hits / samples;
};

/** 선이 지나가는 줄(전체 길이의 70% 이상)이 이어진 구간들 */
const findLineRuns = (
  length: number,
  coverageAt: (position: number) => number,
): LineRun[] => {
  const runs: LineRun[] = [];
  let start = -1;
  for (let position = 0; position <= length; position += 1) {
    const isLine = position < length && coverageAt(position) >= 0.7;
    if (isLine && start < 0) start = position;
    if (!isLine && start >= 0) {
      runs.push({ start, end: position - 1 });
      start = -1;
    }
  }
  return runs;
};

const pickNearest = (
  runs: readonly LineRun[],
  target: number,
  min: number,
  max: number,
): LineRun | undefined =>
  runs
    .filter((run) => center(run) >= min && center(run) <= max)
    .sort(
      (a, b) => Math.abs(center(a) - target) - Math.abs(center(b) - target),
    )[0];

/** 만화 네 칸을 프레임 칸에 꽉 차게(넘치는 쪽은 가운데 기준으로 잘라) 옮긴다. */
export const composePostcard = (
  frame: RasterImage,
  comic: RasterImage,
): RasterImage => {
  const framePanels = findFramePanels(frame);
  const comicPanels = findComicPanels(comic);
  const output = new Uint8ClampedArray(frame.data);

  framePanels.forEach(({ box: target, pixels }, panelIndex) => {
    const source = comicPanels[panelIndex];
    const targetWidth = target.x1 - target.x0 + 1;
    const targetHeight = target.y1 - target.y0 + 1;
    const sourceWidth = source.x1 - source.x0 + 1;
    const sourceHeight = source.y1 - source.y0 + 1;
    const scale = Math.max(
      targetWidth / sourceWidth,
      targetHeight / sourceHeight,
    );
    const offsetX = source.x0 + (sourceWidth - targetWidth / scale) / 2;
    const offsetY = source.y0 + (sourceHeight - targetHeight / scale) / 2;

    for (const index of pixels) {
      const x = index % frame.width;
      const y = (index - x) / frame.width;
      const sourceX = offsetX + (x - target.x0 + 0.5) / scale - 0.5;
      const sourceY = offsetY + (y - target.y0 + 0.5) / scale - 0.5;
      sampleInto(comic, sourceX, sourceY, output, index * 4);
    }
  });

  return { width: frame.width, height: frame.height, data: output };
};

/** 양선형 보간으로 한 픽셀을 읽어 output[offset..]에 쓴다. */
const sampleInto = (
  image: RasterImage,
  fx: number,
  fy: number,
  output: Uint8ClampedArray,
  offset: number,
): void => {
  const x0 = Math.min(image.width - 2, Math.max(0, Math.floor(fx)));
  const y0 = Math.min(image.height - 2, Math.max(0, Math.floor(fy)));
  const ax = Math.min(1, Math.max(0, fx - x0));
  const ay = Math.min(1, Math.max(0, fy - y0));
  const weights = [(1 - ax) * (1 - ay), ax * (1 - ay), (1 - ax) * ay, ax * ay];
  const corners = [
    (y0 * image.width + x0) * 4,
    (y0 * image.width + x0 + 1) * 4,
    ((y0 + 1) * image.width + x0) * 4,
    ((y0 + 1) * image.width + x0 + 1) * 4,
  ];
  for (let channel = 0; channel < 3; channel += 1) {
    let value = 0;
    for (let corner = 0; corner < 4; corner += 1)
      value += image.data[corners[corner] + channel] * weights[corner];
    output[offset + channel] = value;
  }
  output[offset + 3] = 255;
};
