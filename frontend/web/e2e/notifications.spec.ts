import { expect, test } from '@playwright/test';
import { AUTHENTICATED_STORAGE_STATE } from './auth';
import {
  MOCK_SCENARIO_HEADER,
  MOCK_SCENARIOS,
} from '../src/mocks/mockScenarios';

test.describe('피드 인앱 알림', (): void => {
  test.use({ storageState: AUTHENTICATED_STORAGE_STATE });

  test('생성 버튼과 알림을 양쪽에 표시하고 알림창을 열어도 안 읽은 표시를 유지한다', async ({
    page,
  }): Promise<void> => {
    const mutations: string[] = [];
    page.on('request', (request): void => {
      if (
        request.method() === 'PATCH' &&
        request.url().includes('/notifications/')
      )
        mutations.push(request.url());
    });
    await page.goto('/');
    const bell = page.getByRole('button', {
      name: '새 알림 있음, 알림 열기',
      exact: true,
    });
    await expect(bell).toBeVisible();
    const create = await page
      .getByRole('button', { name: '네컷만화 만들기', exact: true })
      .boundingBox();
    const notification = await bell.boundingBox();
    expect(create?.x).toBeLessThan(notification?.x ?? 0);
    await bell.click();
    const sheet = page.getByRole('dialog').filter({
      has: page.getByRole('heading', { name: '알림', exact: true }),
    });
    await expect(sheet.getByRole('listitem')).toHaveCount(6);
    await expect(
      sheet.getByRole('img', { name: '안 읽은 알림', exact: true }),
    ).toHaveCount(6);
    await expect(
      sheet.getByRole('listitem').first().getByRole('button'),
    ).toHaveCSS('background-color', 'rgb(248, 246, 255)');
    await expect(sheet.locator('img')).toHaveCount(7);
    await expect(
      sheet.getByRole('button', { name: '전체 읽기', exact: true }),
    ).toBeEnabled();
    await sheet.getByRole('button', { name: '닫기', exact: true }).click();
    await expect(sheet).toBeHidden();
    await expect(bell).toBeVisible();
    expect(mutations).toHaveLength(0);
  });

  test('선택한 알림 하나만 읽고 피드로 이동하며 돌아오면 그 알림의 점만 사라진다', async ({
    page,
  }): Promise<void> => {
    const mutations: string[] = [];
    page.on('request', (request): void => {
      if (
        request.method() === 'PATCH' &&
        request.url().includes('/notifications/')
      )
        mutations.push(new URL(request.url()).pathname);
    });
    await page.goto('/');
    await page
      .getByRole('button', { name: '새 알림 있음, 알림 열기', exact: true })
      .click();
    const sheet = page.getByRole('dialog').filter({
      has: page.getByRole('heading', { name: '알림', exact: true }),
    });
    await sheet
      .getByRole('button', { name: /네오님이 댓글/ })
      .first()
      .click();
    await expect(page).toHaveURL('/feeds/00000000-0000-4000-8000-000000000001');
    expect(mutations).toEqual([
      '/api/v1/notifications/20000000-0000-4000-8000-000000000001',
    ]);
    await page.goBack();
    await page
      .getByRole('button', { name: '새 알림 있음, 알림 열기', exact: true })
      .click();
    await expect(sheet.getByRole('listitem')).toHaveCount(6);
    await expect(
      sheet.getByRole('img', { name: '안 읽은 알림', exact: true }),
    ).toHaveCount(5);
    const selected = sheet.getByRole('listitem').first();
    await expect(
      selected.getByRole('img', { name: '안 읽은 알림', exact: true }),
    ).toHaveCount(0);
    await expect(selected.getByRole('button')).toHaveCSS(
      'background-color',
      'rgba(0, 0, 0, 0)',
    );
    await selected.getByRole('button').click();
    await expect(page).toHaveURL('/feeds/00000000-0000-4000-8000-000000000001');
    expect(mutations).toHaveLength(1);
  });

  test('목록 조회 실패 후 시트 안에서 재시도한다', async ({
    page,
  }): Promise<void> => {
    await page.setExtraHTTPHeaders({
      [MOCK_SCENARIO_HEADER]: MOCK_SCENARIOS.notificationsListFailure,
    });
    await page.goto('/');
    await page.getByRole('button', { name: '알림 열기', exact: true }).click();
    const sheet = page.getByRole('dialog').filter({
      has: page.getByRole('heading', { name: '알림', exact: true }),
    });
    await expect(sheet.getByRole('alert')).toContainText(
      '알림을 불러오지 못했어요.',
    );
    await page.setExtraHTTPHeaders({});
    await sheet.getByRole('button', { name: '다시 시도', exact: true }).click();
    await expect(
      sheet.getByText('네오님이 댓글을 남겼습니다.').first(),
    ).toBeVisible();
    await expect(
      sheet.getByRole('img', { name: '안 읽은 알림', exact: true }),
    ).toHaveCount(6);
  });

  test('알림을 재조회하지 못해도 기존 목록과 안 읽은 표시를 유지하고 토스트로 안내한다', async ({
    page,
  }): Promise<void> => {
    await page.goto('/');
    const bell = page.getByRole('button', {
      name: '새 알림 있음, 알림 열기',
      exact: true,
    });
    await expect(bell).toBeEnabled();
    await page.setExtraHTTPHeaders({
      [MOCK_SCENARIO_HEADER]: MOCK_SCENARIOS.notificationsListFailure,
    });
    await bell.click();
    const sheet = page.getByRole('dialog').filter({
      has: page.getByRole('heading', { name: '알림', exact: true }),
    });
    await expect(
      page.getByText('알림을 불러오지 못했어요. 다시 시도해 주세요.', {
        exact: true,
      }),
    ).toBeAttached();
    await expect(sheet.getByRole('listitem')).toHaveCount(6);
    await expect(
      sheet.getByRole('img', { name: '안 읽은 알림', exact: true }),
    ).toHaveCount(6);
    await expect(sheet.getByRole('alert')).toHaveCount(0);
    await expect(
      sheet.getByRole('button', { name: '다시 시도', exact: true }),
    ).toHaveCount(0);
    await sheet.getByRole('button', { name: '닫기', exact: true }).click();
    await expect(bell).toBeVisible();
  });

  test('선택한 알림의 읽음 처리가 실패하면 이동하지 않고 같은 알림을 다시 시도한다', async ({
    page,
  }): Promise<void> => {
    await page.setExtraHTTPHeaders({
      [MOCK_SCENARIO_HEADER]: MOCK_SCENARIOS.notificationsReadFailure,
    });
    await page.goto('/');
    await page
      .getByRole('button', { name: '새 알림 있음, 알림 열기', exact: true })
      .click();
    const sheet = page.getByRole('dialog').filter({
      has: page.getByRole('heading', { name: '알림', exact: true }),
    });
    const selected = sheet
      .getByRole('button', { name: /모모님이 좋아요/ })
      .first();
    await selected.click();
    await expect(
      page.getByText('알림을 읽음으로 표시하지 못했어요. 다시 시도해 주세요.', {
        exact: true,
      }),
    ).toBeAttached();
    await expect(page).toHaveURL('/');
    await expect(sheet).toBeVisible();
    await expect(
      sheet.getByRole('img', { name: '안 읽은 알림', exact: true }),
    ).toHaveCount(6);
    await page.setExtraHTTPHeaders({});
    await selected.click();
    await expect(page).toHaveURL('/feeds/00000000-0000-4000-8000-000000000001');
    await page.goBack();
    await page
      .getByRole('button', { name: '새 알림 있음, 알림 열기', exact: true })
      .click();
    await expect(
      sheet.getByRole('img', { name: '안 읽은 알림', exact: true }),
    ).toHaveCount(5);
  });

  test('더 보기로 불러온 알림도 자동으로 읽지 않고 닫으면 알림 버튼으로 초점이 돌아간다', async ({
    page,
  }): Promise<void> => {
    await page.setExtraHTTPHeaders({
      [MOCK_SCENARIO_HEADER]: MOCK_SCENARIOS.notificationsMany,
    });
    const mutations: string[] = [];
    page.on('request', (request): void => {
      if (
        request.method() === 'PATCH' &&
        request.url().includes('/notifications/')
      )
        mutations.push(request.url());
    });
    await page.goto('/');
    const bell = page.getByRole('button', {
      name: '새 알림 있음, 알림 열기',
      exact: true,
    });
    await bell.click();
    const sheet = page.getByRole('dialog').filter({
      has: page.getByRole('heading', { name: '알림', exact: true }),
    });
    await expect(sheet.getByRole('listitem')).toHaveCount(20);
    await sheet.getByRole('button', { name: '더 보기', exact: true }).click();
    await expect(sheet.getByRole('listitem')).toHaveCount(24);
    await expect(
      sheet.getByRole('img', { name: '안 읽은 알림', exact: true }),
    ).toHaveCount(24);
    await expect(
      sheet.getByRole('button', { name: '더 보기', exact: true }),
    ).toHaveCount(0);
    expect(mutations).toHaveLength(0);
    await page.keyboard.press('Escape');
    await expect(sheet).toBeHidden();
    await expect(bell).toBeFocused();
  });

  test('전체 읽기는 한 요청으로 아직 불러오지 않은 페이지까지 읽음 처리한다', async ({
    page,
  }): Promise<void> => {
    await page.setExtraHTTPHeaders({
      [MOCK_SCENARIO_HEADER]: MOCK_SCENARIOS.notificationsMany,
    });
    await page.addInitScript((): void => {
      const browserFetch = window.fetch.bind(window);
      window.fetch = async (
        ...args: Parameters<typeof fetch>
      ): Promise<Response> => {
        const [input, init] = args;
        if (
          init?.method === 'PATCH' &&
          typeof input === 'string' &&
          input.endsWith('/notifications/read-all')
        )
          await new Promise<void>((resolve): void => {
            setTimeout(resolve, 400);
          });
        return browserFetch(...args);
      };
    });
    const mutations: string[] = [];
    page.on('request', (request): void => {
      if (
        request.method() === 'PATCH' &&
        request.url().includes('/notifications/')
      )
        mutations.push(new URL(request.url()).pathname);
    });
    await page.goto('/');
    await page
      .getByRole('button', { name: '새 알림 있음, 알림 열기', exact: true })
      .click();
    const sheet = page.getByRole('dialog').filter({
      has: page.getByRole('heading', { name: '알림', exact: true }),
    });
    await expect(sheet.getByRole('listitem')).toHaveCount(20);
    await sheet.getByRole('button', { name: '전체 읽기', exact: true }).click();
    await expect(
      sheet.getByRole('button', { name: '읽음 처리 중...', exact: true }),
    ).toBeDisabled();
    await expect(
      sheet.getByRole('img', { name: '안 읽은 알림', exact: true }),
    ).toHaveCount(0);
    await expect(
      page.getByRole('button', { name: '알림 열기', exact: true }),
    ).toHaveCount(1);
    await expect(
      sheet.getByRole('button', { name: '전체 읽기', exact: true }),
    ).toBeDisabled();
    const nextPage = page.waitForResponse(
      (response): boolean =>
        response.request().method() === 'GET' &&
        new URL(response.url()).pathname === '/api/v1/notifications' &&
        new URL(response.url()).searchParams.has('cursor'),
    );
    await sheet.getByRole('button', { name: '더 보기', exact: true }).click();
    const data: unknown = await (await nextPage).json();
    expect(data).toMatchObject({
      unreadCount: 0,
      items: Array.from({ length: 4 }, (): { read: boolean } => ({
        read: true,
      })),
    });
    await expect(sheet.getByRole('listitem')).toHaveCount(24);
    await expect(
      sheet.getByRole('img', { name: '안 읽은 알림', exact: true }),
    ).toHaveCount(0);
    expect(mutations).toEqual(['/api/v1/notifications/read-all']);
  });

  test('전체 읽기 실패는 읽음 상태와 배지를 보존하고 버튼으로 다시 시도한다', async ({
    page,
  }): Promise<void> => {
    await page.setExtraHTTPHeaders({
      [MOCK_SCENARIO_HEADER]: MOCK_SCENARIOS.notificationsReadFailure,
    });
    await page.goto('/');
    const bell = page.getByRole('button', {
      name: '새 알림 있음, 알림 열기',
      exact: true,
    });
    await bell.click();
    const sheet = page.getByRole('dialog').filter({
      has: page.getByRole('heading', { name: '알림', exact: true }),
    });
    await sheet.getByRole('button', { name: '전체 읽기', exact: true }).click();
    await expect(
      page.getByText('알림을 읽음으로 표시하지 못했어요. 다시 시도해 주세요.', {
        exact: true,
      }),
    ).toBeAttached();
    await expect(
      sheet.getByRole('img', { name: '안 읽은 알림', exact: true }),
    ).toHaveCount(6);
    await sheet.getByRole('button', { name: '닫기', exact: true }).click();
    await expect(bell).toBeVisible();
    await page.setExtraHTTPHeaders({});
    await bell.click();
    await sheet.getByRole('button', { name: '전체 읽기', exact: true }).click();
    await expect(
      sheet.getByRole('img', { name: '안 읽은 알림', exact: true }),
    ).toHaveCount(0);
    await expect(
      page.getByRole('button', { name: '알림 열기', exact: true }),
    ).toHaveCount(1);
  });

  test('전체 읽기 후 재조회만 실패하면 완료 상태를 유지하고 다시 열어 최신 목록을 확인한다', async ({
    page,
  }): Promise<void> => {
    await page.addInitScript((): void => {
      const browserFetch = window.fetch.bind(window);
      let failNextList = false;
      window.fetch = async (
        ...args: Parameters<typeof fetch>
      ): Promise<Response> => {
        const [input, init] = args;
        const isList =
          typeof input === 'string' &&
          new URL(input, window.location.href).pathname ===
            '/api/v1/notifications' &&
          (init?.method === undefined || init.method === 'GET');
        if (failNextList && isList) {
          failNextList = false;
          return new Response(null, { status: 503 });
        }
        const response = await browserFetch(...args);
        if (
          init?.method === 'PATCH' &&
          typeof input === 'string' &&
          input.endsWith('/notifications/read-all') &&
          response.ok
        )
          failNextList = true;
        return response;
      };
    });
    await page.goto('/');
    await page
      .getByRole('button', { name: '새 알림 있음, 알림 열기', exact: true })
      .click();
    const sheet = page.getByRole('dialog').filter({
      has: page.getByRole('heading', { name: '알림', exact: true }),
    });
    await sheet.getByRole('button', { name: '전체 읽기', exact: true }).click();
    await expect(
      page.getByText(
        '전체 읽기는 완료했어요. 최신 알림은 알림창을 다시 열어 확인해 주세요.',
        { exact: true },
      ),
    ).toBeAttached();
    await expect(sheet.getByRole('listitem')).toHaveCount(6);
    await expect(
      sheet.getByRole('img', { name: '안 읽은 알림', exact: true }),
    ).toHaveCount(0);
    await sheet.getByRole('button', { name: '닫기', exact: true }).click();
    await page.getByRole('button', { name: '알림 열기', exact: true }).click();
    await expect(sheet.getByRole('listitem')).toHaveCount(6);
    await expect(
      sheet.getByRole('img', { name: '안 읽은 알림', exact: true }),
    ).toHaveCount(0);
  });

  test('빈 목록 안내를 표시하고 배경 클릭으로 닫는다', async ({
    page,
  }): Promise<void> => {
    await page.setExtraHTTPHeaders({
      [MOCK_SCENARIO_HEADER]: MOCK_SCENARIOS.notificationsEmpty,
    });
    await page.goto('/');
    await page.getByRole('button', { name: '알림 열기', exact: true }).click();
    const sheet = page.getByRole('dialog').filter({
      has: page.getByRole('heading', { name: '알림', exact: true }),
    });
    await expect(sheet).toContainText('아직 도착한 알림이 없어요.');
    await expect(
      sheet.getByRole('button', { name: '전체 읽기', exact: true }),
    ).toBeDisabled();
    await page.mouse.click(5, 5);
    await expect(sheet).toBeHidden();
  });
});

test('비회원은 생성 버튼을 사용할 수 있지만 알림 버튼과 알림 요청은 없다', async ({
  page,
}): Promise<void> => {
  const notifications: string[] = [];
  page.on('request', (request): void => {
    if (request.url().includes('/api/v1/notifications'))
      notifications.push(request.url());
  });
  await page.goto('/');
  await page
    .getByRole('button', { name: '네컷만화 만들기', exact: true })
    .click();
  await expect(page.getByRole('dialog')).toContainText(
    '로그인 없이 생성할 수 있어요',
  );
  await expect(page.getByRole('button', { name: /알림 열기/ })).toHaveCount(0);
  expect(notifications).toHaveLength(0);
});
