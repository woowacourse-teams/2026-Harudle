import { expect, test } from '@playwright/test';
import {
  MOCK_SCENARIO_HEADER,
  MOCK_SCENARIOS,
} from '../src/mocks/mockScenarios';

test.describe('인증 상태에 따른 리다이렉트', () => {
  test('로그인하지 않은 상태로 홈 화면에 접근하면 로그인 화면으로 이동한다', async ({
    page,
  }) => {
    await page.goto('/');

    await expect(page).toHaveURL('/login');
    await expect(
      page.getByRole('button', { name: '카카오로 시작하기' }),
    ).toBeVisible();
  });

  test('로그인한 상태로 로그인 화면에 접근하면 홈 화면으로 이동한다', async ({
    page,
  }) => {
    await page.addInitScript(() => {
      localStorage.setItem('harudle.has-completed-oauth', 'true');
    });

    await page.goto('/login');

    await expect(page).toHaveURL('/');
    await expect(page.getByLabel('조회할 월')).toBeVisible();
  });
});

test.describe('카카오 로그인', () => {
  test('로그인 버튼을 누르면 카카오 OAuth 경로로 이동한다', async ({
    page,
  }) => {
    await page.setExtraHTTPHeaders({
      [MOCK_SCENARIO_HEADER]: MOCK_SCENARIOS.oauthAuthorization,
    });
    await page.goto('/login');

    const loginButton = page.getByRole('button', {
      name: '카카오로 시작하기',
    });

    await expect(loginButton).toBeVisible();
    await loginButton.click();

    await expect(page).toHaveURL(/\/oauth2\/authorization\/kakao$/);
  });

  test('인증 콜백에서 Access Token 발급에 성공하면 홈으로 이동한다', async ({
    page,
  }) => {
    await page.goto('/auth/callback');

    await expect(page).toHaveURL(/^http:\/\/localhost:5173\/$/);
    await expect(page.getByLabel('조회할 월')).toBeVisible();
  });

  test('인증 콜백에서 Access Token 발급에 실패하면 경고 후 로그인으로 돌아간다', async ({
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
