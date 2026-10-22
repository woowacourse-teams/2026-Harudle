import {
  MOCK_SCENARIO_HEADER,
  MOCK_SCENARIOS,
} from '../src/mocks/mockScenarios';
import { FEED_ACTION_COPY } from '../src/shared/feed/copy';
import { ERROR_MESSAGES } from '../src/shared/errorMessage';
import { expect, test } from '@playwright/test';
import { AUTHENTICATED_STORAGE_STATE } from './auth';

test.use({
  storageState: AUTHENTICATED_STORAGE_STATE,
  viewport: { width: 430, height: 840 },
});

test('공유 바텀시트에서 공개 링크를 복사한다', async ({ page, context }) => {
  await context.grantPermissions(['clipboard-read', 'clipboard-write']);
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

test('더 보기 완료 후 늦게 도착한 좋아요 응답이 목록을 이전 데이터로 되돌리지 않는다', async ({
  page,
}): Promise<void> => {
  await page.clock.setFixedTime(new Date('2026-10-19T12:00:00+09:00'));
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
  const like = articles.first().getByRole('button', { name: /^\d+$/ }).first();
  const initialLikeCount = Number(await like.textContent());
  const initialIcon = await like.locator('img').getAttribute('src');

  // MSW 응답은 그대로 사용하고, 좋아요 응답을 호출자에게 전달하는 시점만 제어한다.
  await page.evaluate((): void => {
    const originalFetch = window.fetch.bind(window);
    const responseGate = new Promise<void>((resolve): void => {
      window.addEventListener(
        'release-feed-like-response',
        (): void => resolve(),
        {
          once: true,
        },
      );
    });
    window.fetch = async (
      ...args: Parameters<typeof fetch>
    ): Promise<Response> => {
      const [input, init] = args;
      const url = new URL(
        input instanceof Request ? input.url : input.toString(),
        window.location.href,
      );
      const method =
        init?.method ?? (input instanceof Request ? input.method : 'GET');
      const response = await originalFetch(...args);
      if (
        method === 'PUT' &&
        /^\/api\/v1\/feeds\/[^/]+\/like$/.test(url.pathname)
      ) {
        await responseGate;
      }
      return response;
    };
  });

  const likeResponse = page.waitForResponse(
    (response): boolean =>
      response.request().method() === 'PUT' &&
      /^\/api\/v1\/feeds\/[^/]+\/like$/.test(new URL(response.url()).pathname),
  );
  await like.click();
  expect((await likeResponse).ok()).toBe(true);
  await expect(like).toBeDisabled();

  try {
    await page.getByRole('button', { name: '더 보기', exact: true }).click();
    await expect(articles).toHaveCount(24);
    await expect(like).toBeDisabled();
  } finally {
    await page.evaluate((): void => {
      window.dispatchEvent(new Event('release-feed-like-response'));
    });
  }

  await expect(like).toBeEnabled();
  await expect(like).toHaveText(String(initialLikeCount + 1));
  expect(await like.locator('img').getAttribute('src')).not.toBe(initialIcon);
  await expect(articles).toHaveCount(24);
  await expect(
    page.getByRole('button', { name: '더 보기', exact: true }),
  ).toHaveCount(0);
  const contents = await articles.allTextContents();
  const requestCount = listRequests.length;

  await page
    .getByRole('navigation')
    .getByRole('button', { name: '설정', exact: true })
    .click();
  await expect(page.getByRole('button', { name: '로그아웃' })).toBeVisible();
  await page
    .getByRole('navigation')
    .getByRole('button', { name: '홈', exact: true })
    .click();

  await expect(articles).toHaveCount(24);
  await expect(like).toHaveText(String(initialLikeCount + 1));
  expect(await articles.allTextContents()).toEqual(contents);
  await expect(
    page.getByRole('button', { name: '더 보기', exact: true }),
  ).toHaveCount(0);
  expect(listRequests).toHaveLength(requestCount);
});

test('피드 목록에서 피드 삭제: 실패하면 열린 모달 안에 오류를 표시하고 재시도할 수 있다', async ({
  page,
}): Promise<void> => {
  await page.setExtraHTTPHeaders({
    [MOCK_SCENARIO_HEADER]: MOCK_SCENARIOS.feedDeleteFailure,
  });
  await page.goto('/');
  await page
    .getByRole('button', { name: '옵션 더보기 아이콘' })
    .first()
    .click();
  await page
    .getByRole('dialog')
    .filter({
      has: page.getByRole('heading', {
        name: FEED_ACTION_COPY.manageTitle,
        exact: true,
      }),
    })
    .getByRole('button', { name: /피드 삭제$/ })
    .click();
  const modal = page.getByRole('dialog').filter({
    has: page.getByRole('heading', {
      name: FEED_ACTION_COPY.deleteConfirmTitle,
      exact: true,
    }),
  });
  const submit = modal.getByRole('button', {
    name: FEED_ACTION_COPY.deleteAction,
    exact: true,
  });
  await submit.click();

  await expect(modal).toBeVisible();
  await expect(modal.getByRole('alert')).toHaveText(
    ERROR_MESSAGES.FEED_DELETION_FAILED,
  );
  await expect(modal.getByRole('alert')).toBeVisible();
  await expect(
    page.getByText(ERROR_MESSAGES.FEED_DELETION_FAILED, { exact: true }),
  ).toHaveCount(1);
  await expect(page).toHaveURL('/');
  await expect(
    modal.getByRole('button', { name: '취소', exact: true }),
  ).toBeEnabled();
  await expect(submit).toBeEnabled();

  await page.setExtraHTTPHeaders({});
  await submit.click();
  await expect(modal).toBeHidden();
  await expect(
    page.getByText(ERROR_MESSAGES.FEED_DELETION_FAILED, { exact: true }),
  ).toHaveCount(0);
});
