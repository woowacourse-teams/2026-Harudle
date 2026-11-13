// AI 결과에서 네 칸 안쪽 그림과 만화 제목 글씨만 떼어 원본 프레임에 합성한다.
// 프레임의 나머지 픽셀(배경, 상단 문구, 캐릭터, 핸들, QR)은 원본 그대로 유지한다.
// 아래 LAYOUT 값은 데모데이 프레임(2:3) 기준이다. 픽셀 값은 가로 1024px 기준이며 실제 프레임 너비에 비례해 늘린다.
// 프레임 디자인을 바꾸면 비율 값(칸 중심, 새·강아지 위치)을 함께 확인한다.
import sharp from "sharp";

sharp.cache(false);
sharp.concurrency(1);

const BASE_WIDTH = 1024;
const PRINT_DPI = 300;
const LAYOUT = {
  panelCenters: [[0.26, 0.27], [0.74, 0.27], [0.26, 0.69], [0.74, 0.69]],
  birdRight: 0.16, // 왼쪽 아래 새 영역 오른쪽 끝
  titleRight: 0.80, // 오른쪽 아래 강아지·QR 시작점. 제목은 이 왼쪽에만 둔다
  px: {
    interiorErode: 10, // 칸 외곽선에서 이만큼 떨어진 안쪽만 교체
    // 생성본 칸 위치가 원본과 이보다 많이 다르면 레이아웃이 무너진 것으로 본다.
    // 운영에서 45px 어긋난 Gemini 결과가 거절돼 60으로 늘렸다. 크게 무너진 경우는 160px 이상이다.
    maxPanelShift: 60,
    titleHeight: 44,
    titleGapAboveHandle: 22,
    belowPanels: 14, // 아래 칸 외곽선에서 제목 검색을 시작하는 거리
    bandGap: 6, // 글씨 줄 사이로 볼 빈 줄 수
    minBandHeight: 25, // 제목 줄로 인정할 최소 높이
    textPad: 3,
  },
};
const scaled = scale => Object.fromEntries(Object.entries(LAYOUT.px).map(([k, v]) => [k, Math.max(1, Math.round(v * scale))]));

/** 4x6 엽서를 300dpi로 출력하려면 짧은 변이 1200px 이상이어야 한다. */
export const MIN_PRINT_WIDTH = 1200;

export class CompositeError extends Error {}

const lum = (r, g, b) => 0.299 * r + 0.587 * g + 0.114 * b;
const isMintRgb = (r, g, b) => g > r + 25 && g > 170 && b > 140;

function view(data, W) {
  return {
    rgb: (x, y) => { const i = (y * W + x) * 3; return [data[i], data[i + 1], data[i + 2]]; },
    mint: (x, y) => { const i = (y * W + x) * 3; return isMintRgb(data[i], data[i + 1], data[i + 2]); },
    lum: (x, y) => { const i = (y * W + x) * 3; return lum(data[i], data[i + 1], data[i + 2]); },
  };
}

function outerBox(v, W, H, [cx, cy]) {
  const scan = (dx, dy) => {
    let x = cx, y = cy, run = 0;
    while (x > 0 && y > 0 && x < W - 1 && y < H - 1) {
      x += dx; y += dy;
      run = v.mint(x, y) ? run + 1 : 0;
      if (run === 3) return dx ? x - 2 * dx : y - 2 * dy;
    }
    throw new CompositeError(`칸 경계를 찾지 못했습니다 (${cx},${cy})`);
  };
  return { x0: scan(-1, 0), x1: scan(1, 0), y0: scan(0, -1), y1: scan(0, 1) };
}

function interiorMask(v, W, H, [cx, cy], erode) {
  const m = new Uint8Array(W * H);
  const stack = [cy * W + cx];
  m[stack[0]] = 1;
  while (stack.length) {
    const i = stack.pop(), x = i % W, y = (i / W) | 0;
    for (const [nx, ny] of [[x + 1, y], [x - 1, y], [x, y + 1], [x, y - 1]]) {
      if (nx < 0 || ny < 0 || nx >= W || ny >= H) continue;
      const j = ny * W + nx;
      if (m[j]) continue;
      if (v.lum(nx, ny) > 200 && !v.mint(nx, ny)) { m[j] = 1; stack.push(j); }
    }
  }
  for (let k = 0; k < erode; k++) {
    const prev = m.slice();
    for (let y = 1; y < H - 1; y++) for (let x = 1; x < W - 1; x++) {
      const i = y * W + x;
      if (prev[i] && !(prev[i - 1] && prev[i + 1] && prev[i - W] && prev[i + W])) m[i] = 0;
    }
  }
  // 마스크 픽셀 목록만 남겨 메모리를 줄인다
  const idx = [];
  for (let i = 0; i < m.length; i++) if (m[i]) idx.push(i);
  return Uint32Array.from(idx);
}

/** 서버 시작 시 한 번 프레임을 분석해 둔다. */
export async function loadFrame(frameBuffer) {
  const { data, info } = await sharp(frameBuffer).removeAlpha().raw().toBuffer({ resolveWithObject: true });
  const W = info.width, H = info.height;
  const px = scaled(W / BASE_WIDTH);
  const v = view(data, W);
  const centers = LAYOUT.panelCenters.map(([u, t]) => [Math.round(u * W), Math.round(t * H)]);
  const boxes = centers.map(c => outerBox(v, W, H, c));
  const masks = centers.map(c => interiorMask(v, W, H, c, px.interiorErode));
  const birdRight = Math.round(W * LAYOUT.birdRight);
  const titleRight = Math.round(W * LAYOUT.titleRight);
  let birdBottom = 0;
  for (let y = Math.round(H * 0.75); y < Math.round(H * 0.95); y++) for (let x = 0; x < birdRight; x++)
    if (v.lum(x, y) < 110) birdBottom = Math.max(birdBottom, y);
  let handleX = W, handleTop = H;
  for (let y = Math.round(H * 0.95); y < H; y++) for (let x = 0; x < W * 0.5; x++)
    if (v.lum(x, y) < 110 && !v.mint(x, y)) { handleX = Math.min(handleX, x); handleTop = Math.min(handleTop, y); }
  if (handleTop === H) throw new CompositeError("프레임에서 핸들 위치를 찾지 못했습니다.");
  return { data, W, H, px, centers, boxes, masks, birdRight, titleRight, birdBottom, handleX, handleTop };
}

function sampler(data, W, H) {
  return (fx, fy) => {
    const x0 = Math.max(0, Math.min(W - 2, Math.floor(fx))), y0 = Math.max(0, Math.min(H - 2, Math.floor(fy)));
    const ax = Math.min(1, Math.max(0, fx - x0)), ay = Math.min(1, Math.max(0, fy - y0));
    const out = [0, 0, 0];
    for (const [dx, dy, w] of [[0, 0, (1 - ax) * (1 - ay)], [1, 0, ax * (1 - ay)], [0, 1, (1 - ax) * ay], [1, 1, ax * ay]]) {
      const i = ((y0 + dy) * W + x0 + dx) * 3;
      out[0] += data[i] * w; out[1] += data[i + 1] * w; out[2] += data[i + 2] * w;
    }
    return out;
  };
}

/**
 * 제목 줄 영역의 검은 픽셀을 8방향으로 이어진 덩어리로 나눈다. 다음 덩어리는 위쪽 그림(새 다리·발 등)이
 * 잘려 들어온 것으로 보고 버린다. 글씨는 영역 윗변 위로 이어지지 않는다.
 *  - 제목 줄보다 완전히 위에 있는 덩어리
 *  - 영역 윗변에 닿고, 그 바로 위 줄에도 검은 픽셀이 이어지는 덩어리
 *  - 영역 윗변에 닿은 채 줄 높이의 40%도 안 되는 작은 덩어리
 * 버린 픽셀은 주변 2px까지 removed에 담는다.
 */
function splitTitleComponents(isDark, region, band, imageWidth) {
  const RW = region.x1 - region.x0 + 1, RH = region.y1 - region.y0 + 1;
  const labels = new Int32Array(RW * RH);
  const comps = [];
  for (let ry = 0; ry < RH; ry++) for (let rx = 0; rx < RW; rx++) {
    if (labels[ry * RW + rx] || !isDark(region.x0 + rx, region.y0 + ry)) continue;
    const comp = { id: comps.length + 1, minX: Infinity, maxX: -1, minY: Infinity, maxY: -1, pixels: [] };
    const stack = [ry * RW + rx];
    labels[stack[0]] = comp.id;
    while (stack.length) {
      const i = stack.pop(), x = i % RW, y = (i / RW) | 0;
      const gx = region.x0 + x, gy = region.y0 + y;
      comp.pixels.push([gx, gy]);
      if (gx < comp.minX) comp.minX = gx; if (gx > comp.maxX) comp.maxX = gx;
      if (gy < comp.minY) comp.minY = gy; if (gy > comp.maxY) comp.maxY = gy;
      for (let dy = -1; dy <= 1; dy++) for (let dx = -1; dx <= 1; dx++) {
        const nx = x + dx, ny = y + dy;
        if (nx < 0 || ny < 0 || nx >= RW || ny >= RH) continue;
        const j = ny * RW + nx;
        if (!labels[j] && isDark(region.x0 + nx, region.y0 + ny)) { labels[j] = comp.id; stack.push(j); }
      }
    }
    comps.push(comp);
  }
  const bandHeight = band[1] - band[0] + 1;
  const continuesAbove = c => c.pixels.some(([x, y]) => y === region.y0
    && [-1, 0, 1].some(dx => isDark(x + dx, region.y0 - 1)));
  const isStray = c => c.maxY < band[0]
    || (c.minY <= region.y0 && (continuesAbove(c) || c.maxY - c.minY + 1 < bandHeight * 0.4));
  const kept = [], removed = new Set();
  for (const c of comps) {
    if (!isStray(c)) { kept.push(c); continue; }
    for (const [x, y] of c.pixels) for (let dy = -2; dy <= 2; dy++) for (let dx = -2; dx <= 2; dx++) removed.add((y + dy) * imageWidth + x + dx);
  }
  return { kept, removed };
}

/** 생성 이미지를 원본 프레임에 합성해 PNG 버퍼를 돌려준다. 레이아웃이 무너졌으면 CompositeError. */
export async function compositeOnFrame(frame, generatedBuffer) {
  const { W, H } = frame;
  const gen = await sharp(generatedBuffer).removeAlpha().resize(W, H, { fit: "fill", kernel: "lanczos3" }).raw().toBuffer();
  const gv = view(gen, W);
  const sample = sampler(gen, W, H);
  const out = Buffer.from(frame.data);

  const genBoxes = frame.centers.map(c => outerBox(gv, W, H, c));
  genBoxes.forEach((g, k) => {
    const o = frame.boxes[k];
    const shift = Math.max(Math.abs(g.x0 - o.x0), Math.abs(g.x1 - o.x1), Math.abs(g.y0 - o.y0), Math.abs(g.y1 - o.y1));
    if (shift > frame.px.maxPanelShift) throw new CompositeError(`생성본 ${k + 1}번 칸 위치가 원본과 ${shift}px 다릅니다.`);
  });

  frame.masks.forEach((mask, k) => {
    const o = frame.boxes[k], g = genBoxes[k];
    const sx = (g.x1 - g.x0) / (o.x1 - o.x0), sy = (g.y1 - g.y0) / (o.y1 - o.y0);
    for (const i of mask) {
      const x = i % W, y = (i / W) | 0;
      const c = sample(g.x0 + (x - o.x0) * sx, g.y0 + (y - o.y0) * sy);
      out[i * 3] = c[0]; out[i * 3 + 1] = c[1]; out[i * 3 + 2] = c[2];
    }
  });

  // 제목: 아래 칸 밑에서 처음 나오는 충분히 높은 글씨 줄
  const isDark = (x, y) => gv.lum(x, y) < 110 && !gv.mint(x, y);
  const { px } = frame;
  const startY = Math.max(genBoxes[2].y1, genBoxes[3].y1) + px.belowPanels;
  const bands = [];
  for (let y = startY, start = -1, gap = 0; y < H; y++) {
    let has = false;
    for (let x = frame.birdRight; x < frame.titleRight && !has; x++) has = isDark(x, y);
    if (has) { if (start < 0) start = y; gap = 0; }
    else if (start >= 0 && ++gap > px.bandGap) { bands.push([start, y - gap]); start = -1; gap = 0; }
  }
  const band = bands.find(([a, b]) => b - a >= px.minBandHeight);
  if (!band) throw new CompositeError("생성본에서 만화 제목 줄을 찾지 못했습니다.");
  // 제목 줄 주변의 검은 덩어리를 나눠, 글씨가 아닌 조각(위쪽 새 발 끝 등)을 버린다
  const region = { x0: 0, y0: band[0] - px.textPad, x1: frame.titleRight - 1, y1: band[1] + px.textPad };
  const { kept, removed } = splitTitleComponents(isDark, region, band, W);
  if (!kept.length) throw new CompositeError("생성본에서 만화 제목 글씨를 찾지 못했습니다.");
  const tx0 = Math.min(...kept.map(c => c.minX)), tx1 = Math.max(...kept.map(c => c.maxX));
  const ty0 = Math.min(...kept.map(c => c.minY)) - px.textPad, ty1 = Math.max(...kept.map(c => c.maxY)) + px.textPad;
  const srcW = tx1 - tx0 + px.textPad * 2, srcH = ty1 - ty0;
  const scale = Math.min(px.titleHeight / srcH, (frame.titleRight - px.textPad * 3 - frame.handleX) / srcW);
  const dw = Math.round(srcW * scale), dh = Math.round(srcH * scale);
  const dx0 = frame.handleX, dy0 = frame.handleTop - px.titleGapAboveHandle - dh;
  for (let y = 0; y < dh; y++) for (let x = 0; x < dw; x++) {
    const sx = tx0 - px.textPad + x / scale, sy = ty0 + y / scale;
    if (removed.has(Math.round(sy) * W + Math.round(sx))) continue;
    const c = sample(sx, sy);
    if (isMintRgb(c[0], c[1], c[2])) continue;
    const a = Math.max(0, Math.min(1, (190 - lum(c[0], c[1], c[2])) / 140));
    if (!a) continue;
    const i = ((dy0 + y) * W + dx0 + x) * 3;
    for (let k = 0; k < 3; k++) out[i + k] = Math.round(out[i + k] * (1 - a) + 25 * a);
  }

  // 포토프린터가 실제 크기로 출력하도록 300dpi 정보를 넣는다
  return sharp(out, { raw: { width: W, height: H, channels: 3 } }).withMetadata({ density: PRINT_DPI }).png().toBuffer();
}
