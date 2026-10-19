import { expect, test } from '@playwright/test';
import { AUTHENTICATED_STORAGE_STATE } from './auth';

const detailPath = '/feeds/00000000-0000-4000-8000-000000000001';

test.describe('피드 상세 뒤로가기', (): void => {
  test.use({ storageState: AUTHENTICATED_STORAGE_STATE });

  test('필터가 적용된 목록에서 진입하면 상세를 새로고침해도 이전 목록으로 돌아간다', async ({
    page,
  }): Promise<void> => {
    const feedPath = '/?categoryId=1&sort=POPULAR';
    await page.goto(feedPath);
    await expect(page.getByRole('article')).toHaveCount(12);
    await page
      .getByRole('article')
      .first()
      .getByRole('button', { name: /^\d+$/ })
      .nth(1)
      .click();
    await expect(page).toHaveURL(/\/feeds\/[^/]+$/);
    await expect(
      page.getByRole('button', { name: '뒤로가기 아이콘' }),
    ).toBeVisible();

    await page.reload();
    await page.getByRole('button', { name: '뒤로가기 아이콘' }).click();

    await expect(page).toHaveURL(feedPath);
    await expect(page.getByRole('article')).toHaveCount(12);
    await expect(page.getByRole('combobox')).toHaveValue('POPULAR');
  });

  test('외부 공유 링크로 진입하면 헤더 뒤로가기는 홈으로 이동하고 상세 기록을 교체한다', async ({
    page,
  }): Promise<void> => {
    const externalUrl = 'https://external.test/';
    await page.route(externalUrl, async (route): Promise<void> => {
      await route.fulfill({
        contentType: 'text/html; charset=utf-8',
        body: `<a href="http://localhost:5173${detailPath}">공유 피드 보기</a>`,
      });
    });
    await page.goto(externalUrl);
    await page.getByRole('link', { name: '공유 피드 보기' }).click();
    await expect(page).toHaveURL(detailPath);

    await page.getByRole('button', { name: '뒤로가기 아이콘' }).click();

    await expect(page).toHaveURL('/');
    await expect(page.getByRole('article')).toHaveCount(20);
    await page.goBack();
    await expect(page).toHaveURL(externalUrl);
  });
});

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
