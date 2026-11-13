import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import { test } from "node:test";
import sharp from "sharp";
import { compositeOnFrame, CompositeError, loadFrame } from "../src/composite.mjs";

// 실제 프레임과 정상 생성본(가짜 AI 결과)으로 칸 판별을 확인한다
const frame = await loadFrame(await readFile(new URL("../assets/frame.webp", import.meta.url)));
const generated = await readFile(new URL("../assets/fake/generated.png", import.meta.url));
const { W, H } = frame;
const base = await sharp(generated).removeAlpha().resize(W, H, { fit: "fill" }).raw().toBuffer();
const png = raw => sharp(raw, { raw: { width: W, height: H, channels: 3 } }).png().toBuffer();

/** 칸 안쪽 사각형을 한 색으로 칠한다(하늘·풀밭처럼 민트와 비슷한 색을 흉내 낸다). */
function paint(raw, box, rgb) {
  const out = Buffer.from(raw);
  for (let y = box.y0; y <= box.y1; y++) for (let x = box.x0; x <= box.x1; x++) {
    const i = (y * W + x) * 3; out[i] = rgb[0]; out[i + 1] = rgb[1]; out[i + 2] = rgb[2];
  }
  return out;
}
/** 그림 전체를 dx, dy만큼 민다. 빈 곳은 민트로 채운다. */
function shift(raw, dx, dy) {
  const out = Buffer.alloc(raw.length);
  for (let y = 0; y < H; y++) for (let x = 0; x < W; x++) {
    const sx = x - dx, sy = y - dy, o = (y * W + x) * 3;
    if (sx < 0 || sy < 0 || sx >= W || sy >= H) { out[o] = 165; out[o + 1] = 252; out[o + 2] = 216; continue; }
    const i = (sy * W + sx) * 3; out[o] = raw[i]; out[o + 1] = raw[i + 1]; out[o + 2] = raw[i + 2];
  }
  return out;
}
const panelInner = k => {
  const b = frame.boxes[k], w = b.x1 - b.x0, h = b.y1 - b.y0;
  return { x0: Math.round(b.x0 + w * 0.15), x1: Math.round(b.x1 - w * 0.15), y0: Math.round(b.y0 + h * 0.3), y1: Math.round(b.y1 - h * 0.15) };
};

test("정상 생성본은 합성된다", async () => {
  const out = await compositeOnFrame(frame, generated);
  assert.ok(out.length > 0);
});

test("칸 안을 하늘색·연두색으로 칠해도 칸 위치를 잘못 잡지 않는다", async () => {
  let raw = paint(base, panelInner(0), [150, 220, 240]); // 하늘색: 예전 판별에서는 민트로 보였다
  raw = paint(raw, panelInner(2), [170, 235, 180]); // 연두색 풀밭
  const out = await compositeOnFrame(frame, await png(raw));
  assert.ok(out.length > 0);
});

test("조금 밀린 생성본(25px)은 받고, 크게 밀린 생성본(90px)은 거절한다", async () => {
  await compositeOnFrame(frame, await png(shift(base, 25, -15)));
  await assert.rejects(compositeOnFrame(frame, await png(shift(base, 90, 0))),
    error => error instanceof CompositeError && /칸 위치가 원본과 9\dpx 다릅니다/.test(error.message));
});

test("칸 외곽선이 없는 그림(칸을 무시하고 한 장으로 그린 경우)은 거절한다", async () => {
  const blank = Buffer.alloc(base.length, 255);
  await assert.rejects(compositeOnFrame(frame, await png(blank)), CompositeError);
});
