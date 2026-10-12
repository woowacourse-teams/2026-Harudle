import { expect, test } from '@playwright/test';
import { AUTHENTICATED_STORAGE_STATE } from './auth';

const detailPath = '/feeds/00000000-0000-4000-8000-000000000001';

test('상세 직접 진입과 새로고침에서 세션을 복원해 본인 피드를 표시한다', async ({
  browser,
}) => {
  const context = await browser.newContext({
    storageState: AUTHENTICATED_STORAGE_STATE,
  });
  try {
    const page = await context.newPage();
    await page.addInitScript((): void => {
      localStorage.removeItem('harudle.has-seen-landing');
    });
    const detailAuthorizations: (string | undefined)[] = [];
    page.on('request', (request): void => {
      if (new URL(request.url()).pathname === `/api/v1${detailPath}`) {
        detailAuthorizations.push(request.headers().authorization);
      }
    });

    await page.goto(detailPath);
    await expect(
      page.getByRole('button', { name: '옵션 더보기 아이콘' }),
    ).toBeVisible();
    await expect(page).toHaveURL(detailPath);
    await page.reload();
    await expect(
      page.getByRole('button', { name: '옵션 더보기 아이콘' }),
    ).toBeVisible();
    await expect(page).toHaveURL(detailPath);
    expect(detailAuthorizations.length).toBeGreaterThanOrEqual(2);
    expect(
      detailAuthorizations.every(
        (value) => value === 'Bearer mock-access-token',
      ),
    ).toBe(true);
  } finally {
    await context.close();
  }
});

test('첫 방문의 비로그인 사용자도 랜딩 이동 없이 공개 상세를 본다', async ({
  page,
}) => {
  await page.goto(detailPath);
  await expect(page.getByRole('article')).toBeVisible();
  await expect(page).toHaveURL(detailPath);
  await expect(
    page.getByRole('button', { name: '옵션 더보기 아이콘' }),
  ).toHaveCount(0);
});
