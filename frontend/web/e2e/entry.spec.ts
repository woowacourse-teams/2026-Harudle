import { expect, test } from '@playwright/test';
import {
  MOCK_SCENARIO_HEADER,
  MOCK_SCENARIOS,
} from '../src/mocks/mockScenarios';

test.describe('하루들 접속', () => {
  for (const entryPath of ['/', '/landing']) {
    test(`저장소가 비어 있어도 세션이 유효하면 ${entryPath}에서 피드에 도착하고 새로고침 후 유지한다`, async ({
      page,
    }) => {
      await page.setExtraHTTPHeaders({
        [MOCK_SCENARIO_HEADER]: MOCK_SCENARIOS.authRefreshSuccess,
      });
      let refreshCount = 0;
      let guestSessionCount = 0;
      page.on('request', (request): void => {
        if (request.url().endsWith('/api/v1/auth/refresh')) refreshCount += 1;
        if (request.url().endsWith('/api/v1/guest/session'))
          guestSessionCount += 1;
      });

      await page.goto(entryPath);

      await expect(page.getByText('피드 화면', { exact: true })).toBeVisible();
      await expect(page).toHaveURL('/');
      expect(
        await page.evaluate(() => [
          localStorage.getItem('harudle.has-ever-logged-in'),
          localStorage.getItem('harudle.has-completed-oauth'),
        ]),
      ).toEqual(['true', 'true']);
      // 이전 문제는 화면 도착 직후 재이동했으므로 안정된 뒤 요청 횟수를 확인한다.
      await page.waitForTimeout(1000);
      expect(refreshCount).toBe(1);
      expect(guestSessionCount).toBe(0);

      await page.evaluate((): void => localStorage.clear());
      await page.reload();

      await expect(page.getByText('피드 화면', { exact: true })).toBeVisible();
      await expect(page).toHaveURL('/');
      await page.waitForTimeout(1000);
      expect(refreshCount).toBe(2);
      expect(guestSessionCount).toBe(0);
    });
  }

  test('로그인 이력만 남아 있어도 유효한 세션으로 인증 표시를 복구한다', async ({
    page,
  }) => {
    await page.addInitScript((): void => {
      localStorage.setItem('harudle.has-ever-logged-in', 'true');
    });
    let refreshCount = 0;
    page.on('request', (request): void => {
      if (request.url().endsWith('/api/v1/auth/refresh')) refreshCount += 1;
    });

    await page.goto('/');

    await expect(page.getByText('피드 화면', { exact: true })).toBeVisible();
    expect(
      await page.evaluate(() =>
        localStorage.getItem('harudle.has-completed-oauth'),
      ),
    ).toBe('true');
    await page.waitForTimeout(1000);
    expect(refreshCount).toBe(1);
  });

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

  test('localStorage에 로그인 이력이 있고 세션이 유효하면 피드 화면을 보여준다', async ({
    page,
  }) => {
    await page.addInitScript(() => {
      localStorage.setItem('harudle.has-ever-logged-in', 'true');
      localStorage.setItem('harudle.has-completed-oauth', 'true');
    });
    const refreshRequests: string[] = [];
    page.on('request', (request) => {
      if (request.url().endsWith('/api/v1/auth/refresh'))
        refreshRequests.push(request.url());
    });

    await page.goto('/');

    await expect(page.getByText('피드 화면', { exact: true })).toBeVisible();
    await expect(page).toHaveURL('/');
    expect(refreshRequests).toHaveLength(1);
  });

  test('로그아웃 후 다시 접속하면 로그인 페이지를 보여주고, 다시 로그인하면 피드 화면을 보여준다', async ({
    page,
  }) => {
    await page.goto('/login');
    await page.getByRole('button', { name: '카카오로 시작하기' }).click();
    await expect(page.getByText('피드 화면', { exact: true })).toBeVisible();
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
    ).toBeNull();
    expect(
      await page.evaluate(() =>
        localStorage.getItem('harudle.has-ever-logged-in'),
      ),
    ).toBe('true');

    await page.goto('/');
    await expect(page).toHaveURL('/login');
    await page.setExtraHTTPHeaders({});
    await page.getByRole('button', { name: '카카오로 시작하기' }).click();
    await expect(page.getByText('피드 화면', { exact: true })).toBeVisible();
  });

  test('세션이 만료된 상태로 접속하면 경고창 없이 로그인 페이지를 보여준다', async ({
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
