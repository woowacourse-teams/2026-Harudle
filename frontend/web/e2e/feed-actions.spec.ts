import { expect, test } from '@playwright/test';
import { AUTHENTICATED_STORAGE_STATE } from './auth';

test.use({
  storageState: AUTHENTICATED_STORAGE_STATE,
  viewport: { width: 430, height: 840 },
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
