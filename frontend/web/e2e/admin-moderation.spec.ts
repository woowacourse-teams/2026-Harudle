import { expect, test, type Page } from '@playwright/test';
import { AUTHENTICATED_STORAGE_STATE } from './auth';
import { MOCK_SCENARIO_HEADER } from '../src/mocks/mockScenarios';

const reportId = '40000000-0000-4000-8000-000000000001';
const feedId = '00000000-0000-4000-8000-000000000002';

test.use({
  viewport: { width: 1600, height: 1000 },
  storageState: async ({ baseURL }, provideStorage): Promise<void> => {
    if (!baseURL) throw new Error('브라우저 테스트 주소가 없습니다.');
    await provideStorage({
      ...AUTHENTICATED_STORAGE_STATE,
      origins: AUTHENTICATED_STORAGE_STATE.origins.map((entry) => ({
        ...entry,
        origin: new URL(baseURL).origin,
      })),
    });
  },
});

const openReport = async (page: Page): Promise<void> => {
  await page.goto('/admin?view=reports');
  await page.getByRole('button', { name: `피드 신고 ${reportId}` }).click();
  await expect(
    page.getByRole('heading', { name: '신고 상세 · 접수' }),
  ).toBeVisible();
};
const confirmAction = async (
  page: Page,
  title: string,
  reason: string,
): Promise<void> => {
  const dialog = page.locator('dialog[open]');
  await expect(
    dialog.getByRole('heading', { name: title, exact: true }),
  ).toBeVisible();
  await expect(dialog.getByRole('button', { name: '확인하기' })).toBeDisabled();
  await dialog.getByRole('textbox', { name: '처리 사유' }).fill(reason);
  await dialog.getByRole('button', { name: '확인하기' }).click();
  await expect(dialog.getByRole('status')).toHaveText('처리가 완료되었어요.');
  await dialog.getByRole('button', { name: '닫기' }).click();
};

test('신고 사유를 확인하고 처리하면 완료 목록으로 이동하고 다시 종결할 수 없다', async ({
  page,
}): Promise<void> => {
  await openReport(page);
  const detail = page.getByLabel('신고 상세');
  await expect(
    detail.getByText('반복되는 광고 내용을 확인해 주세요.'),
  ).toBeVisible();
  await detail.getByRole('button', { name: '처리 완료', exact: true }).click();
  await confirmAction(page, '신고 처리 완료', '반복 광고를 확인하여 처리 완료');
  await expect(
    detail.getByRole('heading', { name: '신고 상세 · 처리 완료' }),
  ).toBeVisible();
  await expect(
    detail.getByRole('button', { name: '처리 완료', exact: true }),
  ).toHaveCount(0);
  await page
    .getByRole('group', { name: '신고 상태 필터' })
    .getByRole('button', { name: '처리 완료', exact: true })
    .click();
  await page.getByRole('button', { name: `피드 신고 ${reportId}` }).click();
  await expect(
    page.getByText('반복 광고를 확인하여 처리 완료', { exact: true }),
  ).toBeVisible();
  await expect(
    page.getByRole('heading', { name: '신고 상세 · 처리 완료' }),
  ).toBeVisible();
});

test('콘텐츠 숨김과 계정 정지를 별개로 처리하고 기존 사용자 관리에서도 해제한다', async ({
  page,
}): Promise<void> => {
  await openReport(page);
  const content = page.getByRole('region', { name: '콘텐츠 조치' });
  const account = page.getByRole('region', { name: '계정 조치' });
  await content
    .getByRole('button', { name: '콘텐츠 숨김', exact: true })
    .click();
  await confirmAction(page, '콘텐츠 숨김', '공개 피드의 광고 숨김');
  await expect(
    content.getByRole('button', { name: '숨김 해제', exact: true }),
  ).toBeEnabled();
  await expect(
    page.getByText('관리자 숨김 상태', { exact: true }),
  ).toBeVisible();
  await account
    .getByRole('button', { name: '사용자 정지', exact: true })
    .click();
  await confirmAction(page, '사용자 정지', '반복 신고로 계정 정지');
  await expect(
    account.getByRole('button', { name: '정지 해제', exact: true }),
  ).toBeEnabled();
  await content.getByRole('button', { name: '숨김 해제', exact: true }).click();
  await confirmAction(page, '숨김 해제', '피드 내용 확인 후 숨김 해제');
  await expect(
    content.getByRole('button', { name: '콘텐츠 숨김', exact: true }),
  ).toBeEnabled();
  await expect(account.getByText('정지 중', { exact: true })).toBeVisible();
  await page
    .getByRole('navigation', { name: '관리자 메뉴' })
    .getByRole('button', { name: '사용자 관리', exact: true })
    .click();
  await page.getByRole('button', { name: '네오 상세 보기' }).click();
  const userAccount = page.getByRole('region', { name: '계정 조치' });
  await userAccount
    .getByRole('button', { name: '정지 해제', exact: true })
    .click();
  await confirmAction(page, '정지 해제', '이의 확인 후 계정 정지 해제');
  await expect(
    userAccount.getByText('정지 없음', { exact: true }),
  ).toBeVisible();
  await expect(
    userAccount.getByRole('button', { name: '사용자 정지', exact: true }),
  ).toBeEnabled();
  await expect(
    page.getByRole('button', { name: '사용 횟수 복구' }),
  ).toBeVisible();
});

test('실패 입력을 보존하고 충돌 후에는 조회만 새로고친 뒤 새 조치를 확인한다', async ({
  page,
}): Promise<void> => {
  let mutationCount = 0;
  page.on('request', (request): void => {
    if (
      request.method() === 'PUT' &&
      new URL(request.url()).pathname ===
        `/api/v1/admin/moderation/FEED/${feedId}`
    )
      mutationCount += 1;
  });
  await openReport(page);
  const content = page.getByRole('region', { name: '콘텐츠 조치' });
  await content
    .getByRole('button', { name: '콘텐츠 숨김', exact: true })
    .click();
  const dialog = page.locator('dialog[open]');
  await dialog
    .getByRole('textbox', { name: '처리 사유' })
    .fill('재시도해도 사유 보존');
  await page.setExtraHTTPHeaders({
    [MOCK_SCENARIO_HEADER]: 'admin-mutation-failure',
  });
  await dialog.getByRole('button', { name: '확인하기' }).click();
  await expect(dialog.getByRole('alert')).toBeVisible();
  await expect(dialog.getByRole('textbox')).toHaveValue('재시도해도 사유 보존');
  await page.setExtraHTTPHeaders({ [MOCK_SCENARIO_HEADER]: 'admin-conflict' });
  await dialog.getByRole('button', { name: '확인하기' }).click();
  await expect(dialog.getByRole('alert')).toContainText('다른 관리자가 변경');
  await expect(dialog.getByRole('button', { name: '확인하기' })).toHaveCount(0);
  await dialog.getByRole('button', { name: '닫기' }).click();
  await expect(
    content.getByRole('button', { name: '콘텐츠 숨김', exact: true }),
  ).toBeDisabled();
  expect(mutationCount).toBe(2);
  await page.setExtraHTTPHeaders({});
  await content
    .getByRole('button', { name: '다시 불러오기', exact: true })
    .click();
  await expect(
    content.getByRole('button', { name: '콘텐츠 숨김', exact: true }),
  ).toBeEnabled();
  expect(mutationCount).toBe(2);
  await content
    .getByRole('button', { name: '콘텐츠 숨김', exact: true })
    .click();
  await confirmAction(page, '콘텐츠 숨김', '새 상태 확인 후 숨김');
  await expect(
    content.getByRole('button', { name: '숨김 해제', exact: true }),
  ).toBeEnabled();
  expect(mutationCount).toBe(3);
});

test('계정 정지 OAuth 콜백은 기존 로그인 기록이 있어도 세션을 복원하지 않는다', async ({
  page,
}): Promise<void> => {
  const sessionRequests: string[] = [];
  page.on('request', (request): void => {
    const path = new URL(request.url()).pathname;
    if (path === '/api/v1/auth/refresh' || path === '/api/v1/auth/csrf')
      sessionRequests.push(path);
  });
  page.once('dialog', async (dialog): Promise<void> => {
    expect(dialog.message()).toBe('관리자에 의해 이용이 제한된 계정이에요.');
    await dialog.accept();
  });
  await page.goto('/auth/callback?error=account_suspended');
  await expect(page).toHaveURL('/login');
  await expect(
    page.getByRole('button', { name: '카카오로 계속하기' }),
  ).toBeVisible();
  expect(sessionRequests).toEqual([]);
});
