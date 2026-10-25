import { expect, test, type Page } from '@playwright/test';
import {
  DIARY_GENERATION_ID_KEY,
  DIARY_GENERATION_TOAST_ID_KEY,
} from '../src/shared/constants';
import {
  MOCK_SCENARIO_HEADER,
  MOCK_SCENARIOS,
} from '../src/mocks/mockScenarios';

const generationId = '70000000-0000-4000-8000-000000000001';

const recordDialogs = (page: Page): string[] => {
  const messages: string[] = [];
  page.on('dialog', async (dialog): Promise<void> => {
    messages.push(dialog.message());
    await dialog.dismiss();
  });
  return messages;
};

for (const { path, hasCompletedOauth } of [
  { path: '/contact', hasCompletedOauth: false },
  { path: '/CoNtAcT/', hasCompletedOauth: true },
]) {
  test(`과거 생성 기록이 있어도 ${path}에서 인증 조회나 강제 이동 없이 문의한다`, async ({
    page,
  }): Promise<void> => {
    await page.addInitScript(
      ({ key, value, completed }): void => {
        localStorage.setItem(key, value);
        if (completed)
          localStorage.setItem('harudle.has-completed-oauth', 'true');
        else localStorage.removeItem('harudle.has-completed-oauth');
      },
      {
        key: DIARY_GENERATION_ID_KEY,
        value: generationId,
        completed: hasCompletedOauth,
      },
    );
    await page.setExtraHTTPHeaders({
      [MOCK_SCENARIO_HEADER]: MOCK_SCENARIOS.authRefreshFailure,
    });
    const dialogs = recordDialogs(page);
    const authenticatedRequests: string[] = [];
    page.on('request', (request): void => {
      const path = new URL(request.url()).pathname;
      if (
        path.startsWith('/api/v1/diary-generations/') ||
        path === '/api/v1/auth/csrf' ||
        path === '/api/v1/auth/refresh'
      ) {
        authenticatedRequests.push(path);
      }
    });

    await page.goto(path);
    await expect(
      page.getByRole('heading', { name: '문의하기', exact: true }),
    ).toBeVisible();
    // 기존 생성 알림의 첫 조회와 인증 실패까지 일어날 수 있는 기간을 확인한다.
    await page.waitForTimeout(6_500);

    await expect(page).toHaveURL(path);
    await expect(
      page.getByRole('button', { name: '메일로 문의하기' }),
    ).toBeEnabled();
    await expect(page.getByText('네컷만화를 만들고 있어요')).toHaveCount(0);
    expect(authenticatedRequests).toEqual([]);
    expect(dialogs).toEqual([]);
    expect(
      await page.evaluate(
        (key): string | null => localStorage.getItem(key),
        DIARY_GENERATION_ID_KEY,
      ),
    ).toBe(generationId);
  });
}

test('기존 화면의 인증 요청이 문의로 이동한 뒤 실패해도 문의와 작성 중인 기록을 유지한다', async ({
  page,
}): Promise<void> => {
  await page.addInitScript(
    ({ generationKey, generation }): void => {
      localStorage.setItem('harudle.has-completed-oauth', 'true');
      localStorage.setItem(generationKey, generation);
    },
    { generationKey: DIARY_GENERATION_ID_KEY, generation: generationId },
  );
  const dialogs = recordDialogs(page);
  await page.goto('/contact');
  await expect(
    page.getByRole('heading', { name: '문의하기', exact: true }),
  ).toBeVisible();

  await page.evaluate((): void => {
    const originalFetch = window.fetch.bind(window);
    window.fetch = (
      input: RequestInfo | URL,
      init?: RequestInit,
    ): Promise<Response> => {
      const url = new URL(
        input instanceof Request ? input.url : String(input),
        window.location.href,
      );
      if (url.pathname === '/api/v1/auth/refresh') {
        document.documentElement.dataset.contactRefreshPending = 'true';
        return new Promise((resolve): void => {
          window.addEventListener(
            'contact-release-refresh',
            (): void => {
              resolve(
                Response.json(
                  {
                    type: 'about:blank',
                    title: 'Expired',
                    status: 401,
                    detail: '세션이 만료되었어요.',
                    instance: '/api/v1/auth/refresh',
                    code: 'INVALID_REFRESH_TOKEN',
                  },
                  { status: 401 },
                ),
              );
            },
            { once: true },
          );
        });
      }
      return originalFetch(input, init);
    };
    // 같은 앱에서 설정으로 이동한 뒤, 지연된 인증 응답보다 먼저 문의를 선택한다.
    window.history.pushState({}, '', '/setting');
    window.dispatchEvent(new PopStateEvent('popstate'));
  });
  await page.waitForFunction(
    (): boolean =>
      document.documentElement.dataset.contactRefreshPending === 'true',
  );
  await expect(page.getByText('네컷만화를 만들고 있어요')).toBeVisible();
  await page.getByRole('link', { name: '문의하기', exact: true }).click();
  await expect(
    page.getByRole('heading', { name: '문의하기', exact: true }),
  ).toBeVisible();
  await expect(page.getByText('네컷만화를 만들고 있어요')).toHaveCount(0);

  await page.evaluate((): void => {
    window.dispatchEvent(new Event('contact-release-refresh'));
  });
  await page.waitForFunction(
    (): boolean => localStorage.getItem('harudle.has-completed-oauth') === null,
  );

  await expect(page).toHaveURL('/contact');
  await expect(
    page.getByRole('button', { name: '메일로 문의하기' }),
  ).toBeEnabled();
  expect(dialogs).toEqual([]);
  expect(
    await page.evaluate(
      (key): string | null => localStorage.getItem(key),
      DIARY_GENERATION_ID_KEY,
    ),
  ).toBe(generationId);
  expect(
    await page.evaluate(
      (key): string | null => localStorage.getItem(key),
      DIARY_GENERATION_TOAST_ID_KEY,
    ),
  ).toBeNull();
});
