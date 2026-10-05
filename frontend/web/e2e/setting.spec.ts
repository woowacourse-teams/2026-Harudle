import { expect, test, type Page } from '@playwright/test';
import {
  MOCK_SCENARIO_HEADER,
  MOCK_SCENARIOS,
} from '../src/mocks/mockScenarios';
import { AUTHENTICATED_STORAGE_STATE } from './auth';

test.use({ storageState: AUTHENTICATED_STORAGE_STATE });

const goToSetting = async (
  page: Page,
  scenario?: (typeof MOCK_SCENARIOS)[keyof typeof MOCK_SCENARIOS],
) => {
  await page.setExtraHTTPHeaders(
    scenario ? { [MOCK_SCENARIO_HEADER]: scenario } : {},
  );
  await page.goto('/setting');
};

const expectProfile = async (page: Page) => {
  await expect(page.getByText('하루들', { exact: true })).toBeVisible();
  await expect(page.getByText('kakao', { exact: true })).toBeVisible();
};

test.describe('설정', () => {
  test('설정 페이지를 열면 로딩 후 내 정보를 보여준다', async ({ page }) => {
    await goToSetting(page);
    const loadingSpinner = page.getByRole('img', { name: '로딩 중' });

    await expect(loadingSpinner).toBeVisible();
    await expectProfile(page);
    await expect(loadingSpinner).toBeHidden();
  });

  test('내 정보를 불러오지 못하면 다시 불러오기 버튼으로 재시도할 수 있다', async ({
    page,
  }) => {
    await goToSetting(page, MOCK_SCENARIOS.profileFailure);

    const errorPage = page.getByRole('alert');
    await expect(errorPage.getByText('설정을 불러오지 못했어요')).toBeVisible();
    await expect(
      errorPage.getByText(
        '설정 정보를 불러오지 못했습니다. 다시 시도해주세요.',
      ),
    ).toBeVisible();

    await page.setExtraHTTPHeaders({});
    await page.getByRole('button', { name: '다시 불러오기' }).click();

    await expectProfile(page);
  });

  test('로그아웃하면 로그인 페이지를 보여준다', async ({ page }) => {
    await goToSetting(page);
    await expectProfile(page);
    await page.setExtraHTTPHeaders({
      [MOCK_SCENARIO_HEADER]: MOCK_SCENARIOS.authRefreshFailure,
    });
    await page.getByRole('button', { name: '로그아웃' }).click();

    await expect(page).toHaveURL('/login');
    await expect(
      page.getByRole('button', { name: '카카오로 시작하기' }),
    ).toBeVisible();
  });

  test('로그아웃에 실패하면 실패 안내를 보여주고 설정 페이지에 머문다', async ({
    page,
  }) => {
    await goToSetting(page, MOCK_SCENARIOS.logoutFailure);
    await expectProfile(page);

    await page.getByRole('button', { name: '로그아웃' }).click();

    await expect(
      page.getByText('로그아웃에 실패했습니다. 다시 시도해주세요.'),
    ).toBeVisible();
    await expect(page).toHaveURL('/setting');
  });
});
