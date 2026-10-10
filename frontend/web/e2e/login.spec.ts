import { expect, test } from '@playwright/test';
import {
  MOCK_SCENARIO_HEADER,
  MOCK_SCENARIOS,
} from '../src/mocks/mockScenarios';

test.describe('로그인 화면', () => {
  test('localStorage에 로그인 이력이 있고, 세션이 만료되면 로그인 화면으로 이동한다', async ({
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

    await expect(page).toHaveURL('/login');
    await expect(
      page.getByRole('button', { name: '카카오로 시작하기' }),
    ).toBeVisible();
  });

  test('세션이 유효하면 로그인 페이지를 직접 열어도 피드 화면을 보여준다', async ({
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

    await expect(page).toHaveURL('/');
    await expect(page.getByText('피드 화면', { exact: true })).toBeVisible();
    expect(refreshRequests).toHaveLength(1);
  });

  test('세션이 만료되면 로그인 주소에서 세션을 확인하고 로그인 페이지를 보여준다', async ({
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
    expect(refreshRequests).toHaveLength(1);
  });
});

test.describe('카카오 로그인', () => {
  test('카카오 로그인 후 로그인 처리에 성공하면 피드 화면을 보여준다', async ({
    page,
  }) => {
    await page.goto('/auth/callback');

    await expect(page).toHaveURL('/');
    await expect(page.getByText('피드 화면', { exact: true })).toBeVisible();
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
