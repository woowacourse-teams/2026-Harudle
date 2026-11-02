import { expect, test, type Locator, type Page } from '@playwright/test';
import { AUTHENTICATED_STORAGE_STATE } from './auth';

test.use({
  storageState: AUTHENTICATED_STORAGE_STATE,
  viewport: { width: 430, height: 840 },
});

const destinations = ['피드 상세', '네컷 모아보기', '설정'] as const;
type Destination = (typeof destinations)[number];

const getFeedList = (page: Page): Locator =>
  page.getByRole('article').first().locator('..');

const getCommentButton = (page: Page, feedIndex: number): Locator =>
  page
    .getByRole('article')
    .nth(feedIndex)
    .getByRole('button', { name: /^\d+$/ })
    .nth(1);

const trackFeedListRequests = (page: Page): URL[] => {
  const requests: URL[] = [];
  page.on('request', (request): void => {
    const url = new URL(request.url());
    if (request.method() === 'GET' && url.pathname === '/api/v1/feeds') {
      requests.push(url);
    }
  });
  return requests;
};

const readFeedScroll = (page: Page): Promise<number> =>
  getFeedList(page).evaluate((element): number => element.scrollTop);

const scrollToFeed = async (page: Page, feedIndex: number): Promise<number> => {
  const commentButton = getCommentButton(page, feedIndex);
  await commentButton.scrollIntoViewIfNeeded();
  await expect(commentButton).toBeInViewport();
  await expect
    .poll((): Promise<number> => readFeedScroll(page))
    .toBeGreaterThan(0);
  return readFeedScroll(page);
};

const expectFeedScroll = async (page: Page, top: number): Promise<void> => {
  await expect
    .poll(async (): Promise<number> =>
      Math.abs((await readFeedScroll(page)) - top),
    )
    .toBeLessThanOrEqual(1);
};

const visitOtherScreen = async (
  page: Page,
  destination: Destination,
  feedIndex: number,
): Promise<void> => {
  if (destination === '피드 상세') {
    await getCommentButton(page, feedIndex).click();
    await expect(page).toHaveURL(/\/feeds\/[^/]+$/);
    await expect(
      page.getByRole('button', { name: '뒤로가기 아이콘' }),
    ).toBeVisible();
    return;
  }

  await page
    .getByRole('navigation')
    .getByRole('button', { name: destination, exact: true })
    .click();

  if (destination === '네컷 모아보기') {
    await expect(page).toHaveURL('/album');
    await expect(page.getByLabel('조회할 월')).toBeVisible();
  } else {
    await expect(page).toHaveURL('/setting');
    await expect(page.getByRole('button', { name: '로그아웃' })).toBeVisible();
  }
};

test.beforeEach(async ({ page }): Promise<void> => {
  await page.clock.setFixedTime(new Date('2026-10-19T12:00:00+09:00'));
});

test.describe('피드 스크롤과 목록 캐시 복원', (): void => {
  test('새로고침하면 필터를 유지하고 누적 목록과 커서를 첫 페이지로 갱신한다', async ({
    page,
  }): Promise<void> => {
    const requests = trackFeedListRequests(page);
    await page.goto('/?categoryId=1&sort=POPULAR');
    await expect(page.getByRole('article')).toHaveCount(12);
    await scrollToFeed(page, 4);
    const requestCount = requests.length;

    await page.getByRole('combobox').locator('..').getByRole('button').click();

    await expect(page.getByRole('article')).toHaveCount(12);
    await expectFeedScroll(page, 0);
    expect(requests).toHaveLength(requestCount + 1);
    expect(requests.at(-1)?.searchParams.get('categoryId')).toBe('1');
    expect(requests.at(-1)?.searchParams.get('sort')).toBe('POPULAR');
    expect(requests.at(-1)?.searchParams.has('cursor')).toBe(false);
    await expect(page).toHaveURL('/?categoryId=1&sort=POPULAR');

    await page.goto('/?sort=POPULAR');
    await expect(page.getByRole('article')).toHaveCount(20);
    await page.getByRole('button', { name: '더 보기', exact: true }).click();
    await expect(page.getByRole('article')).toHaveCount(24);
    await scrollToFeed(page, 23);

    await page.getByRole('combobox').locator('..').getByRole('button').click();

    await expect(page.getByRole('article')).toHaveCount(20);
    await expectFeedScroll(page, 0);
    expect(requests.at(-1)?.searchParams.has('cursor')).toBe(false);
    await page.getByRole('button', { name: '더 보기', exact: true }).click();
    await expect(page.getByRole('article')).toHaveCount(24);
    expect(requests.at(-1)?.searchParams.get('cursor')).toBeTruthy();
  });

  for (const destination of destinations) {
    test(`${destination}에서 화면의 복귀 버튼으로 돌아오면 위치를 복원하고 목록을 재조회하지 않는다`, async ({
      page,
    }): Promise<void> => {
      const requests = trackFeedListRequests(page);
      await page.goto('/');
      await expect(page.getByRole('article')).toHaveCount(20);
      const initialRequestCount = requests.length;
      expect(initialRequestCount).toBeGreaterThan(0);
      const scrollTop = await scrollToFeed(page, 4);
      const contents = await page.getByRole('article').allTextContents();

      await visitOtherScreen(page, destination, 4);
      if (destination === '피드 상세') {
        await page.getByRole('button', { name: '뒤로가기 아이콘' }).click();
      } else {
        await page
          .getByRole('navigation')
          .getByRole('button', { name: '홈', exact: true })
          .click();
      }

      await expect(page).toHaveURL('/');
      await expect(page.getByRole('article')).toHaveCount(20);
      await expectFeedScroll(page, scrollTop);
      expect(await page.getByRole('article').allTextContents()).toEqual(
        contents,
      );
      expect(requests).toHaveLength(initialRequestCount);
    });

    test(`더 보기 후 ${destination}에서 브라우저 뒤로 가기로 돌아오면 누적 목록과 마지막 위치를 복원한다`, async ({
      page,
    }): Promise<void> => {
      const requests = trackFeedListRequests(page);
      await page.goto('/');
      await expect(page.getByRole('article')).toHaveCount(20);
      const initialRequestCount = requests.length;
      expect(initialRequestCount).toBeGreaterThan(0);
      const firstPageMaxScroll = await getFeedList(page).evaluate(
        (element): number => element.scrollHeight - element.clientHeight,
      );

      await page.getByRole('button', { name: '더 보기', exact: true }).click();
      await expect(page.getByRole('article')).toHaveCount(24);
      await expect(
        page.getByRole('button', { name: '더 보기', exact: true }),
      ).toHaveCount(0);
      expect(requests).toHaveLength(initialRequestCount + 1);
      expect(requests.at(-1)?.searchParams.get('cursor')).toBeTruthy();

      const scrollTop = await scrollToFeed(page, 23);
      expect(scrollTop).toBeGreaterThan(firstPageMaxScroll);
      const contents = await page.getByRole('article').allTextContents();
      const requestCountAfterLoadMore = requests.length;

      await visitOtherScreen(page, destination, 23);
      await page.goBack();

      await expect(page).toHaveURL('/');
      await expect(page.getByRole('article')).toHaveCount(24);
      await expectFeedScroll(page, scrollTop);
      expect(await page.getByRole('article').allTextContents()).toEqual(
        contents,
      );
      await expect(
        page.getByRole('button', { name: '더 보기', exact: true }),
      ).toHaveCount(0);
      expect(requests).toHaveLength(requestCountAfterLoadMore);
    });
  }

  for (const filter of ['카테고리', '정렬'] as const) {
    test(`${filter} 변경 시 맨 위로 이동하고 새 조건의 첫 페이지를 조회한다`, async ({
      page,
    }): Promise<void> => {
      const requests = trackFeedListRequests(page);
      await page.goto('/');
      await expect(page.getByRole('article')).toHaveCount(20);
      const initialRequestCount = requests.length;
      expect(initialRequestCount).toBeGreaterThan(0);
      await scrollToFeed(page, 4);

      const categoryId = filter === '카테고리' ? '2' : null;
      const sort = filter === '정렬' ? 'POPULAR' : 'LATEST';
      const response = page.waitForResponse((response): boolean => {
        const url = new URL(response.url());
        return (
          response.request().method() === 'GET' &&
          url.pathname === '/api/v1/feeds' &&
          url.searchParams.get('categoryId') === categoryId &&
          url.searchParams.get('sort') === sort &&
          !url.searchParams.has('cursor')
        );
      });

      if (filter === '카테고리') {
        await page
          .getByRole('main')
          .getByRole('button', { name: '우테코', exact: true })
          .first()
          .click();
      } else {
        await page.getByRole('combobox').selectOption('POPULAR');
      }

      expect((await response).ok()).toBe(true);
      await expect(page.getByRole('article')).toHaveCount(
        filter === '카테고리' ? 12 : 20,
      );
      await expectFeedScroll(page, 0);
      expect(requests).toHaveLength(initialRequestCount + 1);
      expect(requests.at(-1)?.searchParams.get('categoryId')).toBe(categoryId);
      expect(requests.at(-1)?.searchParams.get('sort')).toBe(sort);
      expect(requests.at(-1)?.searchParams.has('cursor')).toBe(false);
    });
  }
});
