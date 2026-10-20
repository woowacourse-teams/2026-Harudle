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
      page
        .getByRole('article')
        .getByRole('button', { name: '옵션 더보기 아이콘' }),
    ).toBeVisible();
    await expect(page).toHaveURL(detailPath);
    await page.reload();
    await expect(
      page
        .getByRole('article')
        .getByRole('button', { name: '옵션 더보기 아이콘' }),
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
    page
      .getByRole('article')
      .getByRole('button', { name: '옵션 더보기 아이콘' }),
  ).toHaveCount(0);
});

test.describe('피드 상세 좋아요', (): void => {
  test.use({ storageState: AUTHENTICATED_STORAGE_STATE });

  test('상세에서 좋아요를 누르거나 취소하고 목록으로 돌아오면 변경된 좋아요가 유지된다', async ({
    page,
  }): Promise<void> => {
    const listRequests: string[] = [];
    page.on('request', (request): void => {
      if (
        request.method() === 'GET' &&
        new URL(request.url()).pathname === '/api/v1/feeds'
      ) {
        listRequests.push(request.url());
      }
    });
    await page.goto('/');
    const articles = page.getByRole('article');
    await expect(articles).toHaveCount(20);
    const firstFeed = articles.first();
    const listLike = firstFeed.getByRole('button', { name: /^\d+$/ }).first();
    const initialCount = Number(await listLike.textContent());
    const requestCount = listRequests.length;

    for (const liked of [true, false]) {
      await firstFeed.getByRole('button', { name: /^\d+$/ }).nth(1).click();
      await expect(page).toHaveURL(/\/feeds\/[^/]+$/);
      const detailLike = page
        .getByRole('article')
        .getByRole('button', { name: /^\d+$/ })
        .first();
      await detailLike.click();
      const expectedCount = String(initialCount + (liked ? 1 : 0));
      await expect(detailLike).toHaveText(expectedCount);
      await expect(detailLike).toBeEnabled();
      const icon = await detailLike.locator('img').getAttribute('src');
      if (icon === null) throw new Error('좋아요 아이콘이 없습니다.');

      await page.getByRole('button', { name: '뒤로가기 아이콘' }).click();
      await expect(page).toHaveURL('/');
      await expect(articles).toHaveCount(20);
      await expect(listLike).toHaveText(expectedCount);
      await expect(listLike.locator('img')).toHaveAttribute('src', icon);
      expect(listRequests).toHaveLength(requestCount);
    }
  });
});
