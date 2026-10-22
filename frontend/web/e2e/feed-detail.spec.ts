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

test.describe('피드 상세 삭제', (): void => {
  test.use({ storageState: AUTHENTICATED_STORAGE_STATE });

  test('상세에서 삭제하고 목록으로 돌아오면 삭제한 피드가 다시 표시되지 않는다', async ({
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
    const options = page.getByRole('button', { name: '옵션 더보기 아이콘' });
    const ownFeedCount = await options.count();
    const ownFeed = articles.filter({ has: options }).first();
    const requestCount = listRequests.length;
    await ownFeed.getByRole('button', { name: /^\d+$/ }).nth(1).click();
    await expect(page).toHaveURL(/\/feeds\/[^/]+$/);
    await expect(articles).toHaveCount(1);

    await page
      .getByRole('article')
      .getByRole('button', { name: '옵션 더보기 아이콘' })
      .click();
    await page
      .getByRole('dialog')
      .filter({
        has: page.getByRole('heading', { name: '피드 관리', exact: true }),
      })
      .getByRole('button', { name: /피드 삭제$/ })
      .click();
    await page
      .getByRole('dialog')
      .filter({
        has: page.getByRole('heading', {
          name: '이 피드를 삭제할까요?',
          exact: true,
        }),
      })
      .getByRole('button', { name: /피드 삭제$/ })
      .click();

    await expect(page).toHaveURL('/');
    await expect(articles).toHaveCount(19);
    await expect(options).toHaveCount(ownFeedCount - 1);
    expect(listRequests).toHaveLength(requestCount);
  });
});
