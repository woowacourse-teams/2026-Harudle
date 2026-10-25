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
  await expect(page.getByRole('strong')).toHaveText('하루들');
  await expect(page.getByText('kakao', { exact: true })).toBeVisible();
};

test.describe('설정', () => {
  test('프로필 사진을 선택하고 되돌려도 아래 입력 요소의 위치가 바뀌지 않는다', async ({
    page,
  }) => {
    await page.setViewportSize({ width: 375, height: 812 });
    await goToSetting(page);
    await page.getByRole('button', { name: '프로필 수정' }).click();

    const uploadButton = page.getByRole('button', { name: '내 사진 불러오기' });
    const nicknameInput = page.getByLabel('닉네임');
    const uploadPosition = await uploadButton.boundingBox();
    const nicknamePosition = await nicknameInput.boundingBox();
    expect(uploadPosition).not.toBeNull();
    expect(nicknamePosition).not.toBeNull();

    await page.getByRole('button', { name: '기니피그 프로필 사진' }).click();
    const restoreButton = page.getByRole('button', { name: '되돌리기' });
    await expect(restoreButton).toBeVisible();
    expect(await uploadButton.boundingBox()).toEqual(uploadPosition);
    expect(await nicknameInput.boundingBox()).toEqual(nicknamePosition);

    await restoreButton.click();
    await expect(restoreButton).toBeHidden();
    expect(await uploadButton.boundingBox()).toEqual(uploadPosition);
    expect(await nicknameInput.boundingBox()).toEqual(nicknamePosition);
  });

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

  test('로그아웃하면 페이지를 다시 불러와 이전 사용자 피드를 초기화하고 로그인 안내를 보여준다', async ({
    page,
  }) => {
    await page.goto('/');
    await expect(page.getByRole('article')).toHaveCount(20);
    await expect(
      page.getByRole('button', { name: '옵션 더보기 아이콘' }).first(),
    ).toBeVisible();
    await page
      .getByRole('navigation')
      .getByRole('button', { name: '설정' })
      .click();
    await expectProfile(page);
    await page.setExtraHTTPHeaders({
      [MOCK_SCENARIO_HEADER]: MOCK_SCENARIOS.authRefreshFailure,
    });
    await Promise.all([
      page.waitForRequest(
        (request): boolean =>
          request.isNavigationRequest() &&
          request.resourceType() === 'document' &&
          new URL(request.url()).pathname === '/',
      ),
      page.getByRole('button', { name: '로그아웃' }).click(),
    ]);

    await expect(page).toHaveURL('/');
    await expect(page.getByRole('combobox')).toBeVisible();
    await expect(page.getByRole('article')).toHaveCount(20);
    await expect(
      page.getByRole('button', { name: '옵션 더보기 아이콘' }),
    ).toHaveCount(0);
    expect(
      await page.evaluate(() => ({
        hasEverLoggedIn: localStorage.getItem('harudle.has-ever-logged-in'),
        hasCompletedOAuth: localStorage.getItem('harudle.has-completed-oauth'),
      })),
    ).toEqual({ hasEverLoggedIn: 'true', hasCompletedOAuth: null });
    await page
      .getByRole('navigation')
      .getByRole('button', { name: '설정' })
      .click();
    await expect(page.getByRole('dialog')).toContainText(
      '로그인하고 계속할까요?',
    );
  });

  test('다른 탭에서 로그아웃하면 기존 토큰이 있어도 다음 인증 요청을 차단한다', async ({
    page,
    context,
  }) => {
    await goToSetting(page);
    await expectProfile(page);
    const otherPage = await context.newPage();
    await goToSetting(otherPage);
    await expectProfile(otherPage);
    await otherPage
      .getByRole('button', { name: '프로필 수정', exact: true })
      .click();
    await otherPage.getByLabel('닉네임').fill('새닉네임');

    await context.setExtraHTTPHeaders({
      [MOCK_SCENARIO_HEADER]: MOCK_SCENARIOS.authRefreshFailure,
    });
    await page.getByRole('button', { name: '로그아웃' }).click();
    await expect(page).toHaveURL('/');
    await expect(page.getByRole('combobox')).toBeVisible();

    const memberRequests: string[] = [];
    otherPage.on('request', (request) => {
      if (new URL(request.url()).pathname === '/api/v1/me/profile') {
        memberRequests.push(request.url());
      }
    });
    await otherPage.getByRole('button', { name: '저장', exact: true }).click();

    await expect(otherPage).toHaveURL('/');
    await expect(otherPage.getByRole('combobox')).toBeVisible();
    expect(memberRequests).toEqual([]);
    await otherPage.close();
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
