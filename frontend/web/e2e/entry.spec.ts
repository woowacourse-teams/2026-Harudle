import { expect, test } from '@playwright/test';
import {
  MOCK_SCENARIO_HEADER,
  MOCK_SCENARIOS,
} from '../src/mocks/mockScenarios';

test.describe('로그인 경험에 따른 기본 주소 진입', () => {
  test('로그인 경험이 없으면 재방문해도 랜딩을 연다', async ({ page }) => {
    await page.goto('/');
    await expect(page).toHaveURL('/landing');
    await expect(page.getByRole('heading', { level: 1 })).toHaveText(
      '우리끼리 통하는네컷만화',
    );

    await page.goto('/');

    await expect(page).toHaveURL('/landing');
  });

  test('기존 로그인 사용자는 세션을 한 번 확인하고 홈을 연다', async ({
    page,
  }) => {
    await page.addInitScript(() => {
      localStorage.setItem('harudle.has-completed-oauth', 'true');
    });
    const refreshRequests: string[] = [];
    page.on('request', (request) => {
      if (request.url().endsWith('/api/v1/auth/refresh'))
        refreshRequests.push(request.url());
    });

    await page.goto('/');

    await expect(page.getByLabel('조회할 월')).toBeVisible();
    await expect(page).toHaveURL('/');
    expect(refreshRequests).toHaveLength(1);
  });

  test('로그아웃 후 재방문은 로그인으로 이동하고 다시 로그인하면 홈을 연다', async ({
    page,
  }) => {
    await page.goto('/login');
    await page.getByRole('button', { name: '카카오로 시작하기' }).click();
    await expect(page.getByLabel('조회할 월')).toBeVisible();
    await page.goto('/setting');
    await expect(page.getByText('kakao', { exact: true })).toBeVisible();

    // Mock 서버에는 세션이 없으므로 로그아웃 후 만료 응답을 명시한다.
    await page.setExtraHTTPHeaders({
      [MOCK_SCENARIO_HEADER]: MOCK_SCENARIOS.authRefreshFailure,
    });

    await page.getByRole('button', { name: '로그아웃', exact: true }).click();
    await expect(page).toHaveURL('/login');
    expect(
      await page.evaluate(() =>
        localStorage.getItem('harudle.has-completed-oauth'),
      ),
    ).toBe('true');

    await page.goto('/');
    await expect(page).toHaveURL('/login');
    await page.setExtraHTTPHeaders({});
    await page.getByRole('button', { name: '카카오로 시작하기' }).click();
    await expect(page.getByLabel('조회할 월')).toBeVisible();
  });

  test('세션 만료는 한 번 확인하고 로그인 화면으로 이동한다', async ({
    page,
  }) => {
    await page.addInitScript(() => {
      localStorage.setItem('harudle.has-completed-oauth', 'true');
    });
    await page.setExtraHTTPHeaders({
      [MOCK_SCENARIO_HEADER]: MOCK_SCENARIOS.authRefreshFailure,
    });
    const refreshRequests: string[] = [];
    const dialogs: string[] = [];
    page.on('request', (request) => {
      if (request.url().endsWith('/api/v1/auth/refresh'))
        refreshRequests.push(request.url());
    });
    page.on('dialog', async (dialog) => {
      dialogs.push(dialog.message());
      await dialog.dismiss();
    });

    await page.goto('/');

    await expect(page).toHaveURL('/login');
    await expect(
      page.getByRole('button', { name: '카카오로 시작하기' }),
    ).toBeVisible();
    expect(refreshRequests).toHaveLength(1);
    expect(dialogs).toEqual([]);
  });

  test('보호된 주소에 처음 접근하면 기본 주소를 거쳐 랜딩으로 이동한다', async ({
    page,
  }) => {
    await page.goto('/setting');
    await expect(page).toHaveURL('/landing');
    await expect(page.getByRole('heading', { level: 1 })).toBeVisible();
  });

  test('로그인 화면을 직접 열 수 있고 로그인 경험이 없으면 기본 주소는 랜딩을 연다', async ({
    page,
  }) => {
    await page.goto('/login');
    await expect(page).toHaveURL('/login');
    await expect(
      page.getByRole('button', { name: '카카오로 시작하기' }),
    ).toBeVisible();
    await page.goto('/');
    await expect(page).toHaveURL('/landing');
  });

  test('공유 링크를 바로 열고 로그인 경험 없이 기본 주소로 돌아오면 랜딩을 연다', async ({
    page,
  }) => {
    const sharedPath = '/shares/06ed972e-0b79-4da0-9716-c9bd8faec85d';
    await page.goto(sharedPath);
    await expect(
      page.getByText('비가 와도, 나는 괜찮았다.', { exact: true }),
    ).toBeVisible();
    await expect(page).toHaveURL(sharedPath);
    await page.getByRole('button', { name: '하루들' }).click();
    await expect(page).toHaveURL('/landing');
  });
});
