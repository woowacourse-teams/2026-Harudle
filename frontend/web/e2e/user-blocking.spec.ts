import { expect, test } from '@playwright/test';
import { AUTHENTICATED_STORAGE_STATE } from './auth';
import {
  MOCK_SCENARIO_HEADER,
  MOCK_SCENARIOS,
} from '../src/mocks/mockScenarios';

test.use({ storageState: AUTHENTICATED_STORAGE_STATE });
const otherFeed = '/feeds/00000000-0000-4000-8000-000000000002';
const ownFeed = '/feeds/00000000-0000-4000-8000-000000000001';

test('목록에서 차단 실패 후 재시도하면 숨기고 설정에서 해제하면 다시 보인다', async ({
  page,
}): Promise<void> => {
  await page.goto('/');
  await page
    .getByRole('button', { name: '피드 더보기', exact: true })
    .first()
    .click();
  await page.getByRole('button', { name: '작성자 차단', exact: true }).click();
  const dialog = page.getByRole('dialog').filter({
    has: page.getByRole('heading', { name: '작성자 차단 확인', exact: true }),
  });
  await expect(dialog).toContainText('네오님을 차단할까요?');
  await expect(dialog).toContainText(
    '로그아웃하면 공개 콘텐츠는 볼 수 있어요.',
  );
  await page.setExtraHTTPHeaders({
    [MOCK_SCENARIO_HEADER]: MOCK_SCENARIOS.blockMutationFailure,
  });
  await dialog.getByRole('button', { name: '차단하기', exact: true }).click();
  await expect(dialog.getByRole('alert')).toBeVisible();
  await page.setExtraHTTPHeaders({});
  await dialog.getByRole('button', { name: '차단하기', exact: true }).click();
  await expect(dialog).toBeHidden();
  await expect(page.locator('article').first()).toBeVisible();
  await expect(page.locator('article').filter({ hasText: '네오' })).toHaveCount(
    0,
  );
  await page.goto('/setting');
  await page.getByRole('button', { name: '차단한 사용자' }).click();
  await page.getByRole('button', { name: '네오 차단 해제' }).click();
  await expect(page.getByText('차단한 사용자가 없어요.')).toBeVisible();
  await page.goto('/');
  await expect(
    page.locator('article').filter({ hasText: '네오' }).first(),
  ).toBeVisible();
});

test('상세 작성자 차단 후에도 비로그인 공개 피드는 유지된다', async ({
  page,
}): Promise<void> => {
  await page.goto(otherFeed);
  await page.getByRole('button', { name: '피드 더보기', exact: true }).click();
  await page.getByRole('button', { name: '작성자 차단', exact: true }).click();
  await page
    .getByRole('dialog')
    .filter({
      has: page.getByRole('heading', { name: '작성자 차단 확인', exact: true }),
    })
    .getByRole('button', { name: '차단하기', exact: true })
    .click();
  await expect(page).toHaveURL('/');
  await expect(page.locator('article').first()).toBeVisible();
  await expect(page.locator('article').filter({ hasText: '네오' })).toHaveCount(
    0,
  );
  await page.evaluate((): void => {
    localStorage.removeItem('harudle.has-completed-oauth');
  });
  await page.setExtraHTTPHeaders({
    [MOCK_SCENARIO_HEADER]: MOCK_SCENARIOS.authRefreshFailure,
  });
  await page.goto(otherFeed);
  await expect(page.getByText('네오', { exact: true }).first()).toBeVisible();
});

test('타인 댓글에서 작성자를 선택하고 취소하면 그대로 남고 확인하면 새 목록으로 이동한다', async ({
  page,
}): Promise<void> => {
  await page.goto(ownFeed);
  await page
    .getByRole('button', { name: '댓글 더보기', exact: true })
    .first()
    .click();
  await page.getByRole('button', { name: '작성자 차단', exact: true }).click();
  const dialog = page.getByRole('dialog').filter({
    has: page.getByRole('heading', { name: '작성자 차단 확인', exact: true }),
  });
  await dialog.getByRole('button', { name: '취소', exact: true }).click();
  await expect(page).toHaveURL(ownFeed);
  await page
    .getByRole('button', { name: '댓글 더보기', exact: true })
    .first()
    .click();
  await page.getByRole('button', { name: '작성자 차단', exact: true }).click();
  await dialog.getByRole('button', { name: '차단하기', exact: true }).click();
  await expect(page).toHaveURL('/');
  await page.goto(ownFeed);
  await expect(
    page.getByText('오늘도 수고했어요!', { exact: true }).first(),
  ).toBeVisible();
  await expect(
    page.getByRole('button', { name: '댓글 더보기', exact: true }),
  ).toHaveCount(0);
});

test('비회원 작성자 차단은 로그인 안내로 연결한다', async ({
  page,
}): Promise<void> => {
  await page.goto(otherFeed);
  await page.evaluate((): void => {
    localStorage.removeItem('harudle.has-completed-oauth');
  });
  await page.setExtraHTTPHeaders({
    [MOCK_SCENARIO_HEADER]: MOCK_SCENARIOS.authRefreshFailure,
  });
  await page.reload();
  let mutations = 0;
  page.on('request', (request): void => {
    if (
      request.method() === 'PUT' &&
      new URL(request.url()).pathname.startsWith('/api/v1/blocks/')
    )
      mutations += 1;
  });
  await page.getByRole('button', { name: '피드 더보기', exact: true }).click();
  await page.getByRole('button', { name: '작성자 차단', exact: true }).click();
  await expect(
    page
      .getByRole('dialog')
      .getByText('작성자를 차단하려면 로그인이 필요해요.'),
  ).toBeVisible();
  await expect(
    page.getByRole('dialog').filter({
      has: page.getByRole('heading', {
        name: '작성자 차단 확인',
        exact: true,
      }),
    }),
  ).toBeHidden();
  expect(mutations).toBe(0);
});
