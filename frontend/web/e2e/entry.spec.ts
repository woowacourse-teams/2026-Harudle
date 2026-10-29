import { expect, test } from '@playwright/test';
import { FEED_COPY } from '../src/pages/feed/copy';
import { NEED_LOGIN_COPY } from '../src/shared/needLoginCopy';
import {
  MOCK_SCENARIO_HEADER,
  MOCK_SCENARIOS,
} from '../src/mocks/mockScenarios';

test.describe('하루들 접속', () => {
  for (const hasLoginHistory of [false, true]) {
    test(`${hasLoginHistory ? '로그아웃 이력이 있는' : '비로그인'} 사용자가 홈의 만들기를 누르면 로그인 안내를 표시한다`, async ({
      page,
    }): Promise<void> => {
      await page.addInitScript((hasHistory): void => {
        if (hasHistory) {
          localStorage.setItem('harudle.has-ever-logged-in', 'true');
        }
      }, hasLoginHistory);
      await page.setExtraHTTPHeaders({
        [MOCK_SCENARIO_HEADER]: MOCK_SCENARIOS.authRefreshFailure,
      });
      const feedUrl = '/?categoryId=1&sort=POPULAR';
      await page.goto(feedUrl);
      await expect(page.getByRole('combobox')).toBeVisible();
      expect(
        await page.evaluate((): string | null =>
          localStorage.getItem('harudle.has-completed-oauth'),
        ),
      ).toBeNull();

      const createButton = page.getByRole('button', {
        name: FEED_COPY.createAction,
        exact: true,
      });
      await createButton.click();
      const sheet = page.getByRole('dialog').filter({
        has: page.getByRole('heading', { name: NEED_LOGIN_COPY.title }),
      });
      await expect(sheet).toBeVisible();
      await expect(sheet).toContainText('네컷만화를 만들려면 로그인해 주세요.');
      await expect(page).toHaveURL(feedUrl);

      await sheet
        .getByRole('button', { name: NEED_LOGIN_COPY.continueBrowsingAction })
        .click();
      await expect(sheet).not.toBeVisible();
      await expect(page).toHaveURL(feedUrl);

      await createButton.click();
      await expect(sheet).toBeVisible();
      await sheet
        .getByRole('button', { name: NEED_LOGIN_COPY.loginAction })
        .click();
      await expect(page).toHaveURL('/login');
      await expect(
        page.getByRole('button', { name: '카카오로 계속하기' }),
      ).toBeVisible();
    });
  }

  test('제거된 일기 공유 링크는 페이지 없음 안내를 표시한다', async ({
    page,
  }) => {
    await page.goto('/shares/06ed972e-0b79-4da0-9716-c9bd8faec85d');
    await expect(
      page.getByRole('heading', { name: '페이지를 찾을 수 없어요' }),
    ).toBeVisible();
  });
  for (const entryPath of ['/', '/landing']) {
    test(`로그인 이력이 없는 ${entryPath} 진입과 저장소를 비운 뒤 새로고침에서 공개 피드를 유지한다`, async ({
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

      await expect(page.getByRole('combobox')).toBeVisible();
      await expect(page).toHaveURL('/');
      expect(
        await page.evaluate(() => [
          localStorage.getItem('harudle.has-ever-logged-in'),
          localStorage.getItem('harudle.has-completed-oauth'),
        ]),
      ).toEqual(entryPath === '/landing' ? ['true', 'true'] : [null, null]);
      // 이전 문제는 화면 도착 직후 재이동했으므로 안정된 뒤 요청 횟수를 확인한다.
      await page.waitForTimeout(1000);
      expect(refreshCount).toBe(entryPath === '/landing' ? 1 : 0);
      expect(guestSessionCount).toBe(0);

      await page.evaluate((): void => localStorage.clear());
      await page.reload();

      await expect(page.getByRole('combobox')).toBeVisible();
      await expect(page).toHaveURL('/');
      await page.waitForTimeout(1000);
      expect(refreshCount).toBe(entryPath === '/landing' ? 1 : 0);
      expect(guestSessionCount).toBe(0);
      expect(
        await page.evaluate((): string | null =>
          localStorage.getItem('harudle.has-completed-oauth'),
        ),
      ).toBeNull();
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

    await expect(page.getByRole('combobox')).toBeVisible();
    expect(
      await page.evaluate(() =>
        localStorage.getItem('harudle.has-completed-oauth'),
      ),
    ).toBe('true');
    await page.waitForTimeout(1000);
    expect(refreshCount).toBe(1);
  });

  for (const landingHistory of [null, 'false', 'true']) {
    test(`기존 랜딩 방문 기록이 ${landingHistory ?? '없는 첫 방문'}이어도 홈에서 공개 피드를 보고 새로고침한다`, async ({
      page,
    }): Promise<void> => {
      await page.addInitScript((history): void => {
        if (history !== null) {
          localStorage.setItem('harudle.has-seen-landing', history);
        }
      }, landingHistory);
      await page.setExtraHTTPHeaders({
        [MOCK_SCENARIO_HEADER]: MOCK_SCENARIOS.authRefreshFailure,
      });
      const guestRequests: string[] = [];
      page.on('request', (request): void => {
        if (new URL(request.url()).pathname.startsWith('/api/v1/guest/')) {
          guestRequests.push(request.url());
        }
      });
      const feedUrl = '/?categoryId=1&sort=POPULAR';
      await page.goto(feedUrl);
      await expect(page).toHaveURL(feedUrl);
      await expect(page.getByRole('combobox')).toHaveValue('POPULAR');
      await expect(page.getByRole('article')).toHaveCount(3);

      await page.reload();
      await expect(page).toHaveURL(feedUrl);
      await expect(page.getByRole('combobox')).toHaveValue('POPULAR');
      await expect(page.getByRole('article')).toHaveCount(3);
      expect(
        await page.evaluate((): string | null =>
          localStorage.getItem('harudle.has-completed-oauth'),
        ),
      ).toBeNull();
      expect(guestRequests).toEqual([]);
    });
  }

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

    await expect(page.getByRole('combobox')).toBeVisible();
    await expect(page).toHaveURL('/');
    expect(refreshRequests).toHaveLength(1);
  });

  test('로그아웃 후 다시 접속하면 공개 피드를 보여주고, 다시 로그인할 수 있다', async ({
    page,
  }) => {
    await page.goto('/login');
    await page.getByRole('button', { name: '카카오로 계속하기' }).click();
    await expect(page.getByRole('combobox')).toBeVisible();
    await page.goto('/setting');
    await expect(page.getByText('kakao', { exact: true })).toBeVisible();

    // Mock 서버에는 세션이 없으므로 로그아웃 후 만료 응답을 명시한다.
    await page.setExtraHTTPHeaders({
      [MOCK_SCENARIO_HEADER]: MOCK_SCENARIOS.authRefreshFailure,
    });

    await page.getByRole('button', { name: '로그아웃', exact: true }).click();
    await expect(page).toHaveURL('/');
    await expect(page.getByRole('combobox')).toBeVisible();
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
    await expect(page).toHaveURL('/');
    await expect(page.getByRole('combobox')).toBeVisible();
    await page.setExtraHTTPHeaders({});
    await page.goto('/login');
    await page.getByRole('button', { name: '카카오로 계속하기' }).click();
    await expect(page.getByRole('combobox')).toBeVisible();
  });

  test('세션이 만료된 상태로 재방문하면 경고창 없이 공개 피드를 보여준다', async ({
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

    await expect(page).toHaveURL('/');
    await expect(page.getByRole('combobox')).toBeVisible();
    expect(refreshRequests).toHaveLength(1);
    expect(dialogs).toEqual([]);
  });

  test('로그인 이력 없이 설정 페이지를 열면 공개 피드를 보여준다', async ({
    page,
  }) => {
    await page.goto('/setting');
    await expect(page).toHaveURL('/');
    await expect(page.getByRole('combobox')).toBeVisible();
  });

  test('로그인 이력이 없어도 URL을 통해 로그인 페이지에 직접 접속할 수 있다', async ({
    page,
  }) => {
    await page.goto('/login');
    await expect(page).toHaveURL('/login');
    await expect(
      page.getByRole('button', { name: '카카오로 계속하기' }),
    ).toBeVisible();
  });
});
