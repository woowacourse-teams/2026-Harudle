import { expect, test } from '@playwright/test';
import { AUTHENTICATED_STORAGE_STATE } from './auth';

test.use({
  storageState: AUTHENTICATED_STORAGE_STATE,
  viewport: { width: 430, height: 840 },
});

test('공유 바텀시트에서 공개 링크를 복사한다', async ({ page, context }) => {
  await context.grantPermissions(['clipboard-read', 'clipboard-write']);
  await page.addInitScript(() => {
    localStorage.setItem('harudle.has-seen-landing', 'true');
  });
  await page.goto('/');
  await page
    .getByRole('article')
    .first()
    .getByRole('button', { name: '공유 아이콘' })
    .click();
  const sheet = page.getByRole('dialog').filter({
    has: page.getByRole('heading', { name: '피드 공유', exact: true }),
  });
  await expect(sheet).toBeVisible();
  await expect(sheet.getByRole('button', { name: '링크 복사' })).toBeVisible();
  await expect(sheet).toHaveCSS('transform', 'matrix(1, 0, 0, 1, 0, 0)');
  await page.screenshot({ path: 'test-results/feed-share-sheet.png' });

  await sheet.getByRole('button', { name: '링크 복사' }).click();
  await expect(sheet.getByRole('button', { name: '복사됨' })).toBeVisible();
  await expect(sheet).toBeVisible();
  await page.screenshot({ path: 'test-results/feed-link-copied.png' });
  expect(await page.evaluate(() => navigator.clipboard.readText())).toBe(
    `${new URL(page.url()).origin}/feeds/00000000-0000-4000-8000-000000000002`,
  );
  await expect(sheet.getByRole('button', { name: '링크 복사' })).toBeVisible();
});

test('본인 피드 삭제를 취소하거나 확인하고 성공한 항목만 제거한다', async ({
  page,
}) => {
  await page.goto('/');
  const articles = page.getByRole('article');
  await expect(articles).toHaveCount(20);
  const ownFeed = articles
    .filter({ has: page.locator('header button') })
    .first();
  await ownFeed.locator('header').getByRole('button').click();
  await page
    .getByRole('dialog')
    .filter({
      has: page.getByRole('heading', { name: '피드 관리', exact: true }),
    })
    .getByRole('button', { name: /피드 삭제$/ })
    .click();
  const confirm = page.getByRole('dialog').filter({
    has: page.getByRole('heading', {
      name: '이 피드를 삭제할까요?',
      exact: true,
    }),
  });
  await expect(confirm).toContainText('앨범의 원본 네컷만화는 그대로 남아요.');
  await confirm.getByRole('button', { name: '취소' }).click();
  await expect(articles).toHaveCount(20);

  await ownFeed.locator('header').getByRole('button').click();
  await page
    .getByRole('dialog')
    .filter({
      has: page.getByRole('heading', { name: '피드 관리', exact: true }),
    })
    .getByRole('button', { name: /피드 삭제$/ })
    .click();
  await page.screenshot({ path: 'test-results/feed-delete-confirm.png' });
  await confirm.getByRole('button', { name: /피드 삭제$/ }).click();
  await expect(articles).toHaveCount(19);
  await expect(confirm).not.toBeVisible();
});
