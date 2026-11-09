import { expect, test } from '@playwright/test';

test('랜딩에서 전체 너비와 모션 감소 포스터를 적용하고 다른 화면에서 원래 너비로 돌아간다', async ({
  page,
  request,
}) => {
  await page.setViewportSize({ width: 1280, height: 900 });
  await page.emulateMedia({ reducedMotion: 'reduce' });
  await page.goto('/landing');

  const root = page.locator('#root');
  const app = page.locator('#root > div').first();
  await expect(root).toHaveCSS('max-width', 'none');
  await expect(app).toHaveCSS('max-width', 'none');

  const video = page.locator('main video');
  await expect(video).toHaveCSS('visibility', 'hidden');
  const background = await video
    .locator('..')
    .evaluate((element) => getComputedStyle(element).backgroundImage);
  const assetUrl = background.match(/url\("?([^")]+)"?\)/)?.[1];
  expect(assetUrl).toBeTruthy();
  if (!assetUrl) throw new Error('랜딩 배경 이미지 주소가 없습니다');
  const response = await request.get(assetUrl);
  expect(response.ok()).toBeTruthy();
  expect(response.headers()['content-type']).toContain('image/');

  await page.getByRole('link', { name: '하루들 시작하기' }).first().click();
  await expect(page).toHaveURL('/');
  await expect(root).toHaveCSS('max-width', '430px');
  await expect(app).toHaveCSS('max-width', '430px');
  await page.goBack();
  await expect(root).toHaveCSS('max-width', 'none');
  await expect(app).toHaveCSS('max-width', 'none');
});
