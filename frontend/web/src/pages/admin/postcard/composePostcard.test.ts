import { describe, expect, it } from '@jest/globals';
import {
  composePostcard,
  findComicPanels,
  PostcardCompositionError,
  type PixelBox,
  type RasterImage,
} from './composePostcard';

type Rgb = readonly [number, number, number];

const MINT: Rgb = [178, 253, 243];
const WHITE: Rgb = [255, 255, 255];
const BLACK: Rgb = [20, 20, 20];
const CREATURE: Rgb = [40, 220, 200];
const PANEL_COLORS: readonly Rgb[] = [
  [255, 200, 200],
  [200, 255, 200],
  [200, 200, 255],
  [255, 255, 180],
];

const createImage = (
  width: number,
  height: number,
  color: Rgb,
): RasterImage => {
  const data = new Uint8ClampedArray(width * height * 4);
  for (let index = 0; index < width * height; index += 1)
    data.set([...color, 255], index * 4);
  return { width, height, data };
};

const fill = (image: RasterImage, box: PixelBox, color: Rgb): void => {
  for (let y = box.y0; y <= box.y1; y += 1) {
    for (let x = box.x0; x <= box.x1; x += 1)
      image.data.set([...color, 255], (y * image.width + x) * 4);
  }
};

const pixelAt = (image: RasterImage, x: number, y: number): number[] => {
  const offset = (y * image.width + x) * 4;
  return Array.from(image.data.slice(offset, offset + 3));
};

/** 민트 바탕에 검은 테두리의 흰 칸 4개, 2번 칸 오른쪽 아래에 걸친 캐릭터가 있는 400x600 프레임 */
const FRAME_PANELS: readonly PixelBox[] = [
  { x0: 20, y0: 150, x1: 195, y1: 330 },
  { x0: 205, y0: 150, x1: 380, y1: 330 },
  { x0: 20, y0: 345, x1: 195, y1: 525 },
  { x0: 205, y0: 345, x1: 380, y1: 525 },
];
const CREATURE_BOX: PixelBox = { x0: 340, y0: 300, x1: 370, y1: 320 };

const createFrame = (): RasterImage => {
  const frame = createImage(400, 600, MINT);
  for (const panel of FRAME_PANELS) {
    fill(frame, panel, BLACK);
    fill(
      frame,
      {
        x0: panel.x0 + 4,
        y0: panel.y0 + 4,
        x1: panel.x1 - 4,
        y1: panel.y1 - 4,
      },
      WHITE,
    );
  }
  fill(frame, { x0: 338, y0: 298, x1: 372, y1: 322 }, BLACK);
  fill(frame, CREATURE_BOX, CREATURE);
  return frame;
};

/** 서비스 그림처럼 바깥 테두리와 가로·세로 구분선으로 나뉜 300x300 2x2 만화. 칸마다 다른 색으로 칠한다. */
interface ComicColors {
  readonly background?: Rgb;
  readonly line?: Rgb;
  readonly panels?: readonly Rgb[];
}

const createComic = ({
  background = WHITE,
  line = BLACK,
  panels = PANEL_COLORS,
}: ComicColors = {}): RasterImage => {
  const comic = createImage(300, 300, background);
  fill(comic, { x0: 10, y0: 10, x1: 290, y1: 270 }, line);
  const inner = [
    [13, 147],
    [153, 287],
  ];
  const rows = [
    [13, 138],
    [144, 267],
  ];
  rows.forEach(([y0, y1], row) =>
    inner.forEach(([x0, x1], column) =>
      fill(comic, { x0, y0, x1, y1 }, panels[row * 2 + column]),
    ),
  );
  return comic;
};

const centerOf = (box: PixelBox): [number, number] => [
  Math.round((box.x0 + box.x1) / 2),
  Math.round((box.y0 + box.y1) / 2),
];

describe('composePostcard', () => {
  it('만화의 네 칸을 읽는 순서대로 프레임의 네 칸에 옮긴다', () => {
    const postcard = composePostcard(createFrame(), createComic());

    FRAME_PANELS.forEach((panel, index) => {
      const [x, y] = centerOf(panel);
      expect(pixelAt(postcard, x, y)).toEqual([...PANEL_COLORS[index]]);
    });
  });

  it('칸에 걸친 프레임 캐릭터와 칸 밖 배경은 원래 프레임 그대로 둔다', () => {
    const postcard = composePostcard(createFrame(), createComic());

    expect(pixelAt(postcard, 355, 310)).toEqual([...CREATURE]);
    expect(pixelAt(postcard, 200, 50)).toEqual([...MINT]);
    expect(pixelAt(postcard, FRAME_PANELS[0].x0 + 1, 240)).toEqual([...BLACK]);
  });

  it('바탕이 어둡고 선이 밝은 예전 그림체도 칸을 나눈다', () => {
    const dark: Rgb = [30, 30, 30];
    const boxes = findComicPanels(
      createComic({
        background: BLACK,
        line: WHITE,
        panels: [dark, dark, dark, dark],
      }),
    );

    expect(boxes).toHaveLength(4);
    expect(boxes[0].x1).toBeLessThan(boxes[1].x0);
    expect(boxes[0].y1).toBeLessThan(boxes[2].y0);
  });

  it('구분선이 없는 그림은 합성하지 않고 오류를 알린다', () => {
    const blank = createImage(300, 300, WHITE);

    expect(() => composePostcard(createFrame(), blank)).toThrow(
      PostcardCompositionError,
    );
  });
});
