import { expect, test } from '@playwright/test';

const VIEWPORT = { width: 390, height: 844 } as const;

test.use({ viewport: VIEWPORT, isMobile: true, hasTouch: true });

test('모바일 피드는 문서가 화면보다 길어지지 않아 화면 전체가 밀려 올라가지 않는다', async ({
  page,
}): Promise<void> => {
  await page.goto('/');
  await expect(page.locator('main article').nth(1)).toBeAttached();

  expect(
    await page.evaluate((): number => document.documentElement.scrollHeight),
  ).toBe(VIEWPORT.height);

  await page.evaluate((): void => window.scrollTo(0, 350));
  const headerTop = await page
    .getByRole('banner')
    .evaluate((header): number => header.getBoundingClientRect().top);
  expect(headerTop).toBe(0);
});
