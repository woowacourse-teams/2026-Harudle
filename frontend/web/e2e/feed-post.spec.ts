import { expect, test } from '@playwright/test';
import { AUTHENTICATED_STORAGE_STATE } from './auth';
import {
  MOCK_SCENARIO_HEADER,
  MOCK_SCENARIOS,
} from '../src/mocks/mockScenarios';
import { FEED_POST_COPY } from '../src/pages/feed-post/copy';

test.use({ storageState: AUTHENTICATED_STORAGE_STATE });
const DIARY_ID = '00000000-0000-4000-8000-000000000001';
const POST_URL = `/diary/${DIARY_ID}/feed-post`;

test('전달값 없이 직접 진입하면 유효하지 않은 접근으로 안내한다', async ({
  page,
}): Promise<void> => {
  await page.goto(POST_URL);
  await expect(page.getByText(FEED_POST_COPY.invalidEntry)).toBeVisible();
  await expect(page.getByRole('radio')).toHaveCount(0);
  await page.getByRole('button', { name: '뒤로가기 아이콘' }).click();
  await expect(page).toHaveURL(`/diary/${DIARY_ID}`);
});

test('전달받은 이미지를 재조회 없이 표시하고 필요한 필드만 한 번 게시한다', async ({
  page,
}): Promise<void> => {
  let detailCount = 0;
  page.on('request', (request): void => {
    if (
      request.method() === 'GET' &&
      new URL(request.url()).pathname === `/api/v1/diaries/${DIARY_ID}`
    )
      detailCount += 1;
  });
  await page.goto(`/diary/${DIARY_ID}`);
  await expect(page.getByRole('img', { name: '네컷만화' })).toBeVisible();
  const initialDetailCount = detailCount;
  await page
    .getByRole('button', { name: FEED_POST_COPY.publishAction, exact: true })
    .click();
  await expect(page.getByRole('img', { name: '네컷만화' })).toBeVisible();
  await expect(page.getByRole('radio', { name: '일상' })).not.toBeChecked();
  expect(detailCount).toBe(initialDetailCount);
  await page.getByRole('radio', { name: '우테코' }).check();
  let publishCount = 0;
  page.on('request', (request): void => {
    if (
      request.method() === 'POST' &&
      new URL(request.url()).pathname === '/api/v1/feeds'
    )
      publishCount += 1;
  });
  const responsePromise = page.waitForResponse(
    (response): boolean =>
      response.request().method() === 'POST' &&
      new URL(response.url()).pathname === '/api/v1/feeds',
  );
  await page
    .getByRole('button', { name: FEED_POST_COPY.publishAction, exact: true })
    .dblclick();
  const response = await responsePromise;
  expect(response.status()).toBe(201);
  expect(response.request().postDataJSON()).toEqual({
    diaryId: DIARY_ID,
    categoryId: '2',
  });
  expect(response.request().headers()['authorization']).toBe(
    'Bearer mock-access-token',
  );
  expect(response.request().headers()['x-xsrf-token']).toBeUndefined();
  const body: unknown = await response.json();
  expect(body).toMatchObject({
    category: { id: '2', name: '우테코' },
    isMine: true,
    likeCount: 0,
    commentCount: 0,
  });
  expect(body).not.toHaveProperty('title');
  expect(body).not.toHaveProperty('sourceText');
  await expect(page).toHaveURL('/');
  expect(publishCount).toBe(1);

  const detail: unknown = await page.evaluate(
    async (diaryId): Promise<unknown> => {
      const response = await fetch(`/api/v1/diaries/${diaryId}`, {
        headers: { Authorization: 'Bearer mock-access-token' },
      });
      return response.json();
    },
    DIARY_ID,
  );
  expect(detail).toMatchObject({ publishedFeedId: expect.any(String) });
});

test('게시 실패 후 카테고리를 유지하며 사용자가 재시도할 수 있다', async ({
  page,
}): Promise<void> => {
  await page.setExtraHTTPHeaders({
    [MOCK_SCENARIO_HEADER]: MOCK_SCENARIOS.feedPublishFailure,
  });
  await page.goto(`/diary/${DIARY_ID}`);
  await page
    .getByRole('button', { name: FEED_POST_COPY.publishAction, exact: true })
    .click();
  await page.getByRole('radio', { name: '우테코' }).check();
  const dialogPromise = page.waitForEvent('dialog');
  const publishClick = page
    .getByRole('button', { name: FEED_POST_COPY.publishAction })
    .click();
  const dialog = await dialogPromise;
  expect(dialog.type()).toBe('alert');
  expect(dialog.message()).toBe('피드에 올리지 못했어요. 다시 시도해 주세요.');
  await dialog.accept();
  await publishClick;
  await expect(page.getByRole('radio', { name: '우테코' })).toBeChecked();
  await page.setExtraHTTPHeaders({});
  await page
    .getByRole('button', { name: FEED_POST_COPY.publishAction })
    .click();
  await expect(page).toHaveURL('/');
});
