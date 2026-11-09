import { expect, test } from '@playwright/test';
import { AUTHENTICATED_STORAGE_STATE } from './auth';
import {
  MOCK_SCENARIO_HEADER,
  MOCK_SCENARIOS,
} from '../src/mocks/mockScenarios';

test.describe('피드 인앱 알림', (): void => {
  test.use({ storageState: AUTHENTICATED_STORAGE_STATE });

  test('생성 버튼은 왼쪽, 알림은 오른쪽에 표시하고 열면 자동 읽음 후 피드로 이동한다', async ({
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
    expect(mutations).toHaveLength(0);
    const create = await page
      .getByRole('button', { name: '네컷만화 만들기', exact: true })
      .boundingBox();
    const notification = await bell.boundingBox();
    expect(create?.x).toBeLessThan(notification?.x ?? 0);
    await bell.click();
    const sheet = page.getByRole('dialog').filter({
      has: page.getByRole('heading', { name: '알림', exact: true }),
    });
    await expect(
      sheet.getByText('네오님이 댓글을 남겼습니다.').first(),
    ).toBeVisible();
    await expect(
      sheet.getByText('모모님이 좋아요를 눌렀습니다.').first(),
    ).toBeVisible();
    await expect(sheet.locator('img')).toHaveCount(7); // 닫기 아이콘과 프로필 여섯 개, 피드 썸네일 없음.
    await expect.poll((): number => mutations.length).toBe(6);
    await expect(
      page.getByRole('button', { name: '알림 열기', exact: true }),
    ).toHaveCount(1);
    await sheet.getByRole('button', { name: '닫기', exact: true }).click();
    await expect(sheet).toBeHidden();
    await page.getByRole('button', { name: '알림 열기', exact: true }).click();
    await sheet
      .getByRole('button', { name: /네오님이 댓글/ })
      .first()
      .click();
    await expect(page).toHaveURL('/feeds/00000000-0000-4000-8000-000000000001');
    expect(mutations).toHaveLength(6);
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
  });

  test('알림을 재조회하지 못해도 기존 목록이 유지되고 토스트로 안내한다', async ({
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
    await expect(sheet.getByRole('alert')).toHaveCount(0);
    await expect(
      sheet.getByRole('button', { name: '다시 시도', exact: true }),
    ).toHaveCount(0);
    await sheet.getByRole('button', { name: '닫기', exact: true }).click();
    await expect(bell).toBeVisible();
  });

  test('읽음 응답을 기다리지 않고 피드로 이동해도 표시한 알림은 읽음 처리된다', async ({
    page,
  }): Promise<void> => {
    await page.addInitScript((): void => {
      const browserFetch = window.fetch.bind(window);
      window.fetch = async (
        ...args: Parameters<typeof fetch>
      ): Promise<Response> => {
        const [input, init] = args;
        if (
          init?.method === 'PATCH' &&
          typeof input === 'string' &&
          input.includes('/notifications/')
        ) {
          await new Promise<void>((resolve): void => {
            setTimeout(resolve, 800);
          });
        }
        return browserFetch(...args);
      };
    });
    let readCount = 0;
    page.on('response', (response): void => {
      if (
        response.request().method() === 'PATCH' &&
        response.url().includes('/notifications/') &&
        response.ok()
      )
        readCount += 1;
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
    await expect.poll((): number => readCount).toBe(6);
    await page.goBack();
    await expect(
      page.getByRole('button', { name: '알림 열기', exact: true }),
    ).toBeEnabled();
    await expect(
      page.getByRole('button', {
        name: '새 알림 있음, 알림 열기',
        exact: true,
      }),
    ).toHaveCount(0);
  });

  test('읽음 일부 실패 시 목록과 배지를 유지하고 다시 열 때 실패한 알림만 재시도한다', async ({
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
    await expect(
      page.getByText(
        '일부 알림을 읽음으로 표시하지 못했어요. 알림을 다시 열면 재시도해요.',
        { exact: true },
      ),
    ).toBeAttached();
    await expect(
      sheet.getByText('네오님이 댓글을 남겼습니다.').first(),
    ).toBeVisible();
    await sheet.getByRole('button', { name: '닫기', exact: true }).click();
    await expect(
      page.getByRole('button', {
        name: '새 알림 있음, 알림 열기',
        exact: true,
      }),
    ).toBeVisible();
    await page.setExtraHTTPHeaders({});
    await page
      .getByRole('button', { name: '새 알림 있음, 알림 열기', exact: true })
      .click();
    await expect(
      page.getByRole('button', { name: '알림 열기', exact: true }),
    ).toHaveCount(1);
  });

  test('2페이지에 미읽음 알림이 있으면 알림창을 열어도 1페이지의 20개만 읽음 처리된다', async ({
    page,
  }): Promise<void> => {
    await page.setExtraHTTPHeaders({
      [MOCK_SCENARIO_HEADER]: MOCK_SCENARIOS.notificationsMany,
    });
    let readCount = 0;
    page.on('response', (response): void => {
      if (
        response.request().method() === 'PATCH' &&
        response.url().includes('/notifications/') &&
        response.ok()
      )
        readCount += 1;
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
    await expect.poll((): number => readCount).toBe(20);
    await sheet.getByRole('button', { name: '닫기', exact: true }).click();
    await expect(bell).toBeVisible();

    const reloaded = page.waitForResponse(
      (response): boolean =>
        response.request().method() === 'GET' &&
        new URL(response.url()).pathname === '/api/v1/notifications',
    );
    await bell.click();
    const data: unknown = await (await reloaded).json();
    expect(data).toMatchObject({
      unreadCount: 4,
      items: Array.from({ length: 20 }, (): { read: boolean } => ({
        read: true,
      })),
    });
    await expect(sheet.getByRole('listitem')).toHaveCount(20);
    await expect(
      sheet.getByRole('button', { name: '더 보기', exact: true }),
    ).toBeVisible();
    await sheet.getByRole('button', { name: '닫기', exact: true }).click();
    await expect(bell).toBeVisible();
    expect(readCount).toBe(20);
  });

  test('불러오지 않은 알림은 미읽음으로 유지하고 더 보기를 누른 뒤 읽는다', async ({
    page,
  }): Promise<void> => {
    await page.setExtraHTTPHeaders({
      [MOCK_SCENARIO_HEADER]: MOCK_SCENARIOS.notificationsMany,
    });
    await page.goto('/');
    await page
      .getByRole('button', { name: '새 알림 있음, 알림 열기', exact: true })
      .click();
    const sheet = page.getByRole('dialog').filter({
      has: page.getByRole('heading', { name: '알림', exact: true }),
    });
    await expect(sheet.getByRole('listitem')).toHaveCount(20);
    await expect(
      sheet.getByRole('button', { name: '더 보기', exact: true }),
    ).toBeEnabled();
    await expect(
      page.getByRole('button', {
        name: '새 알림 있음, 알림 열기',
        exact: true,
      }),
    ).toHaveCount(1);
    await sheet.getByRole('button', { name: '더 보기', exact: true }).click();
    await expect(sheet.getByRole('listitem')).toHaveCount(24);
    await expect(
      page.getByRole('button', { name: '알림 열기', exact: true }),
    ).toHaveCount(1);
    await expect(
      sheet.getByRole('button', { name: '더 보기', exact: true }),
    ).toHaveCount(0);
    await page.keyboard.press('Escape');
    await expect(sheet).toBeHidden();
    await expect(
      page.getByRole('button', { name: '알림 열기', exact: true }),
    ).toBeFocused();
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
    '네컷만화를 만들려면 로그인해 주세요.',
  );
  await expect(page.getByRole('button', { name: /알림 열기/ })).toHaveCount(0);
  expect(notifications).toHaveLength(0);
});
