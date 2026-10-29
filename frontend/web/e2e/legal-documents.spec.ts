import { expect, test } from '@playwright/test';
import {
  MOCK_SCENARIO_HEADER,
  MOCK_SCENARIOS,
} from '../src/mocks/mockScenarios';
test('비로그인 상태에서 로그인 화면의 문서 링크로 전문을 열람한다', async ({
  page,
}): Promise<void> => {
  let authenticationRequests = 0;
  page.on('request', (request): void => {
    if (new URL(request.url()).pathname === '/api/v1/auth/refresh')
      authenticationRequests += 1;
  });
  await page.goto('/login');
  await expect(
    page.getByText('동의한 것으로 간주됩니다.', { exact: false }),
  ).toHaveCount(0);
  await page.getByRole('link', { name: '이용약관', exact: true }).click();
  await expect(page).toHaveURL('/legal/terms');
  await expect(
    page.getByRole('heading', { name: '하루들 서비스 이용약관', exact: true }),
  ).toBeVisible();
  await expect(page.getByText('2026-10-22', { exact: true })).toBeVisible();
  expect(authenticationRequests).toBe(0);
  await page.getByRole('button', { name: '뒤로 가기', exact: true }).click();
  await expect(page).toHaveURL('/login');
  await page
    .getByRole('link', { name: '개인정보처리방침', exact: true })
    .click();
  await expect(
    page.getByRole('heading', {
      name: '하루들 개인정보 처리방침',
      exact: true,
    }),
  ).toBeVisible();
  await expect(page.getByText('2026-08-24', { exact: true })).toBeVisible();
  expect(authenticationRequests).toBe(0);
});
test('문서 불러오기 오류는 화면 안에서 재시도하고 버전 URL도 공개 열람한다', async ({
  page,
}): Promise<void> => {
  await page.setExtraHTTPHeaders({
    [MOCK_SCENARIO_HEADER]: MOCK_SCENARIOS.legalDocumentsFailure,
  });
  await page.goto('/legal/terms');
  await expect(page.getByRole('alert')).toContainText(
    '문서를 불러오지 못했어요.',
  );
  await page.setExtraHTTPHeaders({});
  await page
    .getByRole('button', { name: '다시 불러오기', exact: true })
    .click();
  await expect(
    page.getByRole('heading', { name: '하루들 서비스 이용약관', exact: true }),
  ).toBeVisible();
  await page.goto('/legal/privacy/2026-08-24');
  await expect(
    page.getByRole('heading', {
      name: '하루들 개인정보 처리방침',
      exact: true,
    }),
  ).toBeVisible();
});
