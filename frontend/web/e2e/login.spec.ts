import { expect, test } from '@playwright/test';
import {
  MOCK_SCENARIO_HEADER,
  MOCK_SCENARIOS,
} from '../src/mocks/mockScenarios';

test.beforeEach(async ({ page }): Promise<void> => {
  await page.addInitScript((): void => {
    localStorage.setItem('harudle.has-seen-landing', 'true');
  });
});

test.describe('로그인 화면', () => {
  test('세션이 만료되어도 공개 피드를 보고 설정 클릭 시 로그인 화면으로 이동할 수 있다', async ({
    page,
  }) => {
    await page.addInitScript(() => {
      localStorage.setItem('harudle.has-ever-logged-in', 'true');
      localStorage.setItem('harudle.has-completed-oauth', 'true');
    });
    await page.setExtraHTTPHeaders({
      [MOCK_SCENARIO_HEADER]: MOCK_SCENARIOS.authRefreshFailure,
    });
    await page.goto('/');

    await expect(page).toHaveURL('/');
    await expect(page.getByRole('combobox')).toBeVisible();
    await page
      .getByRole('navigation')
      .getByRole('button', { name: '설정' })
      .click();
    await page
      .getByRole('dialog')
      .getByRole('button', { name: '카카오로 로그인' })
      .click();
    await expect(page).toHaveURL('/login');
    await expect(
      page.getByRole('button', { name: '카카오로 시작하기' }),
    ).toBeVisible();
  });

  test('로그인 이력이 있어도 로그인 페이지를 직접 열면 자동 재발급 없이 로그인 화면을 보여준다', async ({
    page,
  }) => {
    await page.addInitScript(() => {
      localStorage.setItem('harudle.has-ever-logged-in', 'true');
      localStorage.setItem('harudle.has-completed-oauth', 'true');
    });

    const refreshRequests: string[] = [];
    page.on('request', (request) => {
      if (request.url().endsWith('/api/v1/auth/refresh')) {
        refreshRequests.push(request.url());
      }
    });
    await page.goto('/login');

    await expect(page).toHaveURL('/login');
    await expect(
      page.getByRole('button', { name: '카카오로 시작하기' }),
    ).toBeVisible();
    expect(refreshRequests).toHaveLength(0);
  });

  test('세션이 만료되어도 로그인 주소에서는 자동 재발급 없이 로그인 화면을 보여준다', async ({
    page,
  }) => {
    await page.addInitScript(() => {
      localStorage.setItem('harudle.has-ever-logged-in', 'true');
      localStorage.setItem('harudle.has-completed-oauth', 'true');
    });
    await page.setExtraHTTPHeaders({
      [MOCK_SCENARIO_HEADER]: MOCK_SCENARIOS.authRefreshFailure,
    });
    const refreshRequests: string[] = [];
    page.on('request', (request) => {
      if (request.url().endsWith('/api/v1/auth/refresh')) {
        refreshRequests.push(request.url());
      }
    });

    await page.goto('/login');

    await expect(
      page.getByRole('button', { name: '카카오로 시작하기' }),
    ).toBeVisible();
    await expect(page).toHaveURL('/login');
    expect(refreshRequests).toHaveLength(0);
  });
});

test.describe('카카오 로그인', () => {
  test('카카오 로그인 후 로그인 처리에 성공하면 피드 화면을 보여준다', async ({
    page,
  }) => {
    await page.goto('/auth/callback');

    await expect(page).toHaveURL('/');
    await expect(page.getByRole('combobox')).toBeVisible();
  });

  test('카카오 로그인 후 로그인 처리에 실패하면 실패 안내를 보여주고 로그인 페이지로 돌아간다', async ({
    page,
  }) => {
    await page.setExtraHTTPHeaders({
      [MOCK_SCENARIO_HEADER]: MOCK_SCENARIOS.authRefreshFailure,
    });

    const dialogPromise = page.waitForEvent('dialog');
    await page.goto('/auth/callback');

    const dialog = await dialogPromise;

    expect(dialog.message()).toBe(
      '로그인에 실패했습니다. 다시 로그인해주세요.',
    );
    await dialog.accept();

    await expect(page).toHaveURL(/\/login$/);
    await expect(
      page.getByRole('button', { name: '카카오로 시작하기' }),
    ).toBeVisible();
  });
});
