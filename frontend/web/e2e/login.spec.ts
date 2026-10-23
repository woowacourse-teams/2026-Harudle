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
      page.getByRole('button', { name: '카카오로 계속하기' }),
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
      page.getByRole('button', { name: '카카오로 계속하기' }),
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
      page.getByRole('button', { name: '카카오로 계속하기' }),
    ).toBeVisible();
    await expect(page).toHaveURL('/login');
    expect(refreshRequests).toHaveLength(0);
  });
});

test.describe('소셜 로그인', () => {
  for (const [provider, label] of [
    ['kakao', '카카오로 계속하기'],
    ['google', 'Google로 계속하기'],
    ['apple', 'Apple로 계속하기'],
  ] as const) {
    test(`${label} 성공 후에만 최근 로그인 방법을 저장한다`, async ({
      page,
    }): Promise<void> => {
      await page.goto('/login');
      expect(
        await page.evaluate((): string | null =>
          localStorage.getItem('harudle.last-login-provider'),
        ),
      ).toBeNull();
      const refreshHeaders: (string | undefined)[] = [];
      page.on('request', (request): void => {
        if (request.url().endsWith('/api/v1/auth/refresh')) {
          refreshHeaders.push(request.headers()['x-xsrf-token']);
        }
      });
      await page.getByRole('button', { name: label }).click();
      await expect(page).toHaveURL('/auth/callback');
      await expect(page.getByRole('heading')).toHaveCount(0);
      expect(
        await page.evaluate((): string | null =>
          localStorage.getItem('harudle.last-login-provider'),
        ),
      ).toBeNull();
      await expect(page).toHaveURL('/');
      expect(
        await page.evaluate((): string | null =>
          localStorage.getItem('harudle.last-login-provider'),
        ),
      ).toBe(provider);
      expect(
        await page.evaluate((): string | null =>
          sessionStorage.getItem('harudle.pending-login-provider'),
        ),
      ).toBeNull();
      expect(refreshHeaders).toEqual(['mock-csrf-token']);

      await page.goto('/login');
      await expect(page.getByRole('button', { name: label })).toHaveAttribute(
        'aria-describedby',
        'recent-login',
      );
      await expect(page.getByText('최근 로그인', { exact: true })).toHaveCount(
        1,
      );
    });
  }

  test('카카오 로그인 후 로그인 처리에 성공하면 피드 화면을 보여준다', async ({
    page,
  }) => {
    await page.goto('/auth/callback');

    await expect(page).toHaveURL('/');
    await expect(page.getByRole('combobox')).toBeVisible();
  });

  test('토큰 발급 실패 시 이전 로그인 방법을 유지하고 로그인 화면으로 돌아간다', async ({
    page,
  }) => {
    await page.setExtraHTTPHeaders({
      [MOCK_SCENARIO_HEADER]: MOCK_SCENARIOS.authRefreshFailure,
    });

    await page.addInitScript((): void => {
      localStorage.setItem('harudle.last-login-provider', 'kakao');
      sessionStorage.setItem('harudle.pending-login-provider', 'apple');
    });
    const dialogPromise = page.waitForEvent('dialog');
    await page.goto('/auth/callback');
    const dialog = await dialogPromise;
    await dialog.accept();
    await expect(page).toHaveURL('/login');
    expect(
      await page.evaluate((): string | null =>
        localStorage.getItem('harudle.last-login-provider'),
      ),
    ).toBe('kakao');

    await expect(page).toHaveURL(/\/login$/);
    await expect(
      page.getByRole('button', { name: '카카오로 계속하기' }),
    ).toBeVisible();
  });

  test('OAuth 오류가 있으면 토큰 요청 없이 실패 안내를 보여준다', async ({
    page,
  }): Promise<void> => {
    const refreshRequests: string[] = [];
    page.on('request', (request): void => {
      if (request.url().endsWith('/api/v1/auth/refresh'))
        refreshRequests.push(request.url());
    });
    const dialogPromise = page.waitForEvent('dialog');
    await page.goto('/auth/callback?error=oauth_failed');
    const dialog = await dialogPromise;
    expect(dialog.message()).toBe(
      '로그인에 실패했습니다. 다시 로그인해주세요.',
    );
    await dialog.accept();
    expect(refreshRequests).toHaveLength(0);
    await expect(page).toHaveURL('/login');
  });
});
