import { expect, test } from '@playwright/test';
import {
  MOCK_SCENARIO_HEADER,
  MOCK_SCENARIOS,
} from '../src/mocks/mockScenarios';

test.describe('하루들 접속', () => {
  test('localStorage에 로그인 이력이 없으면, 랜딩 페이지를 보여준다', async ({
    page,
  }) => {
    await page.goto('/');
    await expect(page).toHaveURL('/landing');
    await expect(page.getByRole('heading', { level: 1 })).toHaveText(
      '우리끼리 통하는네컷만화',
    );

    await page.goto('/');

    await expect(page).toHaveURL('/landing');
  });

  test('localStorage에 로그인 이력이 있고 세션이 유효하면 홈 화면을 보여준다', async ({
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

  test('로그아웃 후 다시 접속하면 로그인 페이지를 보여주고, 다시 로그인하면 홈 화면을 보여준다', async ({
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

  test('세션이 만료된 상태로 접속하면 경고창 없이 로그인 페이지를 보여준다', async ({
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

  test('로그인 이력 없이 설정 페이지를 열면 랜딩 페이지를 보여준다', async ({
    page,
  }) => {
    await page.goto('/setting');
    await expect(page).toHaveURL('/landing');
    await expect(page.getByRole('heading', { level: 1 })).toBeVisible();
  });

  test('로그인 이력이 없어도 URL을 통해 로그인 페이지에 직접 접속할 수 있다', async ({
    page,
  }) => {
    await page.goto('/login');
    await expect(page).toHaveURL('/login');
    await expect(
      page.getByRole('button', { name: '카카오로 시작하기' }),
    ).toBeVisible();
  });

  test('로그인 이력 없이 공유받은 만화를 볼 수 있고, 하루들 버튼을 누르면 랜딩 페이지를 보여준다', async ({
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
