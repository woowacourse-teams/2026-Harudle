import { expect, test } from '@playwright/test';
import { AUTHENTICATED_STORAGE_STATE } from './auth';
import {
  MOCK_SCENARIO_HEADER,
  MOCK_SCENARIOS,
} from '../src/mocks/mockScenarios';

test.use({
  storageState: async ({ baseURL }, provideState): Promise<void> => {
    const origin = new URL(baseURL ?? 'http://localhost:5173').origin;
    await provideState({
      ...AUTHENTICATED_STORAGE_STATE,
      origins: AUTHENTICATED_STORAGE_STATE.origins.map(
        (entry): typeof entry => ({ ...entry, origin }),
      ),
    });
  },
});

for (const path of [
  '/',
  '/feeds/00000000-0000-4000-8000-000000000002',
  '/album',
  '/setting/blocked-users',
  '/admin',
]) {
  test(`세션을 복원한 회원도 ${path} 진입 전에 동의를 확인한다`, async ({
    page,
  }): Promise<void> => {
    await page.addInitScript((): void => {
      localStorage.removeItem('harudle.has-completed-oauth');
    });
    await page.setExtraHTTPHeaders({
      [MOCK_SCENARIO_HEADER]: MOCK_SCENARIOS.agreementRequired,
    });
    const checks: string[] = [];
    const memberReads: string[] = [];
    page.on('request', (request): void => {
      const pathname = new URL(request.url()).pathname;
      if (pathname === '/api/v1/auth/refresh') checks.push('session');
      if (pathname === '/api/v1/me/agreements') checks.push('agreement');
      if (
        ['/api/v1/me', '/api/v1/diaries', '/api/v1/blocks'].includes(
          pathname,
        ) ||
        pathname.startsWith('/api/v1/admin/')
      )
        memberReads.push(pathname);
    });

    await page.goto(path);

    await expect(page).toHaveURL('/consent');
    await expect(
      page.getByRole('checkbox', { name: '[필수] 이용약관 동의', exact: true }),
    ).toBeVisible();
    expect(checks.slice(0, 2)).toEqual(['session', 'agreement']);
    expect(memberReads).toEqual([]);
  });
}

test('기존 회원은 전문 열람 후 두 항목을 직접 확인하고 원래 설정 화면으로 돌아간다', async ({
  page,
}): Promise<void> => {
  await page.setExtraHTTPHeaders({
    [MOCK_SCENARIO_HEADER]: MOCK_SCENARIOS.agreementRequired,
  });
  let profileReads = 0;
  const submitted: unknown[] = [];
  page.on('request', (request): void => {
    const path = new URL(request.url()).pathname;
    if (path === '/api/v1/me') profileReads += 1;
    if (path === '/api/v1/me/agreements' && request.method() === 'PUT')
      submitted.push(request.postDataJSON());
  });
  await page.goto('/setting?from=direct#profile');
  await expect(page).toHaveURL('/consent');
  const termsCheckbox = page.getByRole('checkbox', {
    name: '[필수] 이용약관 동의',
    exact: true,
  });
  const ageCheckbox = page.getByRole('checkbox', {
    name: '[필수] 만 14세 이상입니다',
    exact: true,
  });
  const selectAll = page.getByRole('checkbox', {
    name: '모두 확인하고 동의하기',
    exact: true,
  });
  await expect(selectAll).not.toBeChecked();
  await expect(termsCheckbox).not.toBeChecked();
  await expect(ageCheckbox).not.toBeChecked();
  expect(profileReads).toBe(0);
  await page
    .getByRole('link', { name: '이용약관 전문 보기', exact: true })
    .click();
  await expect(page).toHaveURL('/legal/terms/2026-10-22');
  await page.getByRole('button', { name: '뒤로 가기', exact: true }).click();
  await expect(page).toHaveURL('/consent');
  await expect(termsCheckbox).not.toBeChecked();
  await termsCheckbox.check();
  await expect(selectAll).toHaveJSProperty('indeterminate', true);
  await expect(
    page.getByRole('button', { name: '동의하고 시작하기', exact: true }),
  ).toBeDisabled();
  await ageCheckbox.check();
  await expect(selectAll).toBeChecked();
  await selectAll.uncheck();
  await expect(termsCheckbox).not.toBeChecked();
  await expect(ageCheckbox).not.toBeChecked();
  await selectAll.check();
  await expect(termsCheckbox).toBeChecked();
  await expect(ageCheckbox).toBeChecked();
  await expect(selectAll).toHaveJSProperty('indeterminate', false);
  await page
    .getByRole('button', { name: '동의하고 시작하기', exact: true })
    .click();
  await expect(page).toHaveURL('/setting?from=direct#profile');
  await expect(
    page.getByRole('button', { name: '프로필 수정', exact: true }),
  ).toBeVisible();
  expect(submitted).toHaveLength(1);
  expect(submitted[0]).toMatchObject({
    termsVersion: '2026-10-22',
    privacyNoticeVersion: '2026-08-24',
    accepted: true,
    ageConfirmed: true,
  });
});

test('동의 저장 중 제출·이동을 잠그고 실패 뒤 선택을 유지하여 재시도한다', async ({
  page,
}): Promise<void> => {
  await page.setExtraHTTPHeaders({
    [MOCK_SCENARIO_HEADER]: MOCK_SCENARIOS.agreementMutationFailure,
  });
  let submissions = 0;
  page.on('request', (request): void => {
    if (
      request.method() === 'PUT' &&
      new URL(request.url()).pathname === '/api/v1/me/agreements'
    )
      submissions += 1;
  });
  await page.goto('/setting');
  const terms = page.getByRole('checkbox', {
    name: '[필수] 이용약관 동의',
    exact: true,
  });
  const age = page.getByRole('checkbox', {
    name: '[필수] 만 14세 이상입니다',
    exact: true,
  });
  await terms.check();
  await age.check();
  await page
    .getByRole('button', { name: '동의하고 시작하기', exact: true })
    .click();
  await expect(
    page.getByRole('button', { name: '동의하는 중…', exact: true }),
  ).toBeDisabled();
  await expect(
    page.getByRole('button', { name: '로그아웃하고 둘러보기', exact: true }),
  ).toBeDisabled();
  await expect(
    page.getByRole('link', { name: '개인정보처리방침 전문 보기', exact: true }),
  ).toHaveAttribute('aria-disabled', 'true');
  await expect(
    page.getByRole('checkbox', { name: '모두 확인하고 동의하기', exact: true }),
  ).toBeDisabled();
  await expect(page.getByRole('alert')).toContainText(
    '동의를 저장하지 못했어요.',
  );
  await expect(terms).toBeChecked();
  await expect(age).toBeChecked();
  expect(submissions).toBe(1);
  await page.setExtraHTTPHeaders({
    [MOCK_SCENARIO_HEADER]: MOCK_SCENARIOS.agreementRequired,
  });
  await page
    .getByRole('button', { name: '동의하고 시작하기', exact: true })
    .click();
  await expect(page).toHaveURL('/setting');
  expect(submissions).toBe(2);
});

test('거절 시 로그아웃 실패는 유지하고 성공한 뒤 유효했던 회원 세션을 복원하지 않고 랜딩으로 나간다', async ({
  page,
}): Promise<void> => {
  await page.setExtraHTTPHeaders({
    [MOCK_SCENARIO_HEADER]: MOCK_SCENARIOS.agreementRequired,
  });
  await page.goto('/setting');
  const decline = page.getByRole('button', {
    name: '로그아웃하고 둘러보기',
    exact: true,
  });
  await expect(decline).toBeVisible();
  await page.setExtraHTTPHeaders({
    [MOCK_SCENARIO_HEADER]: MOCK_SCENARIOS.logoutFailure,
  });
  await decline.click();
  await expect(page.getByRole('alert')).toContainText('로그아웃하지 못했어요.');
  await expect(page).toHaveURL('/consent');
  // 회원 세션이 살아 있다면 랜딩에서도 복원되는 시나리오로 전환한다.
  await page.setExtraHTTPHeaders({
    [MOCK_SCENARIO_HEADER]: MOCK_SCENARIOS.authRefreshSuccess,
  });
  const invalidRefresh = page.waitForResponse(
    (response): boolean =>
      new URL(response.url()).pathname === '/api/v1/auth/refresh' &&
      response.status() === 401,
  );
  await decline.click();
  await invalidRefresh;
  await expect(page).toHaveURL('/landing');
  await expect(
    page.getByRole('navigation', { name: '서비스 안내', exact: true }),
  ).toBeVisible();
  expect(
    await page.evaluate((): string | null =>
      localStorage.getItem('harudle.has-completed-oauth'),
    ),
  ).toBeNull();
});

test('신규 회원은 로그인 콜백 뒤 약관을 확인하고 피드로 간다', async ({
  page,
}): Promise<void> => {
  await page.setExtraHTTPHeaders({
    [MOCK_SCENARIO_HEADER]: MOCK_SCENARIOS.agreementRequired,
  });
  await page.goto('/login');
  await page.evaluate((): void => {
    localStorage.clear();
    localStorage.setItem('harudle.has-seen-landing', 'true');
    sessionStorage.clear();
  });
  await page.reload();
  let refreshRequests = 0;
  page.on('request', (request): void => {
    if (new URL(request.url()).pathname === '/api/v1/auth/refresh')
      refreshRequests += 1;
  });
  await page
    .getByRole('button', { name: '카카오로 시작하기', exact: true })
    .click();
  await expect(page).toHaveURL('/consent');
  await page
    .getByRole('checkbox', { name: '[필수] 이용약관 동의', exact: true })
    .check();
  await page
    .getByRole('checkbox', { name: '[필수] 만 14세 이상입니다', exact: true })
    .check();
  await page
    .getByRole('button', { name: '동의하고 시작하기', exact: true })
    .click();
  await expect(page.getByRole('combobox')).toBeVisible();
  await expect(page).toHaveURL('/');
  expect(refreshRequests).toBe(1);
});
