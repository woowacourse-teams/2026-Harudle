import { DIARY_GENERATING_COPY } from '../src/pages/diary-generating/copy';
import { DIARY_WRITE_COPY } from '../src/pages/diary-write/copy';
import { HOME_COPY } from '../src/pages/home/copy';
import { expect, test, type Page } from '@playwright/test';
import {
  MOCK_SCENARIO_HEADER,
  MOCK_SCENARIOS,
} from '../src/mocks/mockScenarios';
import { AUTHENTICATED_STORAGE_STATE } from './auth';

test.use({ storageState: AUTHENTICATED_STORAGE_STATE });

const TODAY = new Date('2026-08-30T12:00:00+09:00');
const VALID_DIARY_CONTENT =
  '오늘은 친구와 공원을 산책하며 즐거운 이야기를 나누었다.';
const GENERATED_DIARY_TITLE = '오늘 하루의 소중한 기록';
const GENERATION_ERROR_MESSAGE =
  '일기를 만드는 중 문제가 발생했습니다. 다시 시도해주세요.';

const goToDiaryWritePage = async (
  page: Page,
  options: { controlClock?: boolean; generationFailure?: boolean } = {},
) => {
  if (options.controlClock) {
    await page.clock.install({ time: TODAY });
  } else {
    await page.clock.setFixedTime(TODAY);
  }

  if (options.generationFailure) {
    await page.setExtraHTTPHeaders({
      [MOCK_SCENARIO_HEADER]: MOCK_SCENARIOS.diaryGenerationFailure,
    });
  }

  await page.goto('/diary-write');
};

const submitDiary = async (page: Page, content = VALID_DIARY_CONTENT) => {
  await page.getByRole('textbox').fill(content);
  await page.locator('form').getByRole('button').click();
};

test.describe('일기 생성', () => {
  test('일기를 작성하면 생성 과정을 거쳐 상세 페이지에서 결과를 확인할 수 있다', async ({
    page,
  }) => {
    await goToDiaryWritePage(page, { controlClock: true });
    await submitDiary(page);

    await expect(page).toHaveURL('/diary-generating');
    await expect(page.getByText(DIARY_GENERATING_COPY.steps[0])).toBeVisible();

    await page.clock.fastForward(3_000);
    await expect(page.getByText(DIARY_GENERATING_COPY.steps[1])).toBeVisible();
    await page.clock.fastForward(3_000);
    await expect(page.getByText(DIARY_GENERATING_COPY.steps[2])).toBeVisible();
    await page.clock.fastForward(3_000);
    await expect(page.getByText(DIARY_GENERATING_COPY.steps[3])).toBeVisible();

    await expect(page.getByText(DIARY_GENERATING_COPY.steps[4])).toBeVisible({
      timeout: 12_000,
    });
    await page.clock.fastForward(2_000);

    await expect(page).toHaveURL(/\/diary\/[0-9a-f-]+$/);
    await expect(page.getByText(GENERATED_DIARY_TITLE)).toBeVisible();
    await expect(page.getByText('2026-08-30')).toBeVisible();
    await expect(page.getByRole('img', { name: '네컷만화' })).toBeVisible();
    await expect(page.getByText(VALID_DIARY_CONTENT)).toBeVisible();
  });

  test('10자 미만의 일기는 제출할 수 없다', async ({ page }) => {
    await goToDiaryWritePage(page);
    await submitDiary(page, '123456789');

    await expect(page.getByText(DIARY_WRITE_COPY.minLengthError)).toBeVisible();
    await expect(page).toHaveURL('/diary-write');
    await expect(page.getByRole('textbox')).toHaveValue('123456789');
  });

  test('일기 생성에 실패하면 작성했던 내용으로 다시 작성할 수 있다', async ({
    page,
  }) => {
    await goToDiaryWritePage(page, { generationFailure: true });
    await submitDiary(page);

    await expect(page).toHaveURL('/diary-generating');
    const errorPage = page.getByRole('alert');
    await expect(
      errorPage.getByText(DIARY_GENERATING_COPY.errorTitle),
    ).toBeVisible();
    await expect(errorPage.getByText(GENERATION_ERROR_MESSAGE)).toBeVisible();
    await expect(
      errorPage.getByText(DIARY_GENERATING_COPY.errorDescription),
    ).toBeVisible();

    await page
      .getByRole('button', { name: DIARY_GENERATING_COPY.editAction })
      .click();

    await expect(page).toHaveURL('/diary-write');
    await expect(page.getByRole('textbox')).toHaveValue(VALID_DIARY_CONTENT);
  });

  test('생성 중 홈으로 이동해도 생성 상태와 결과가 반영된다', async ({
    page,
  }) => {
    await goToDiaryWritePage(page);
    await submitDiary(page);

    await expect(page).toHaveURL('/diary-generating');
    await page.getByRole('button', { name: '뒤로 가기' }).click();

    await expect(page).toHaveURL('/');
    await expect(page.getByTestId('diary-generation-skeleton')).toBeVisible();
    await expect(
      page.getByText(
        `${HOME_COPY.remainingUsage.before}3${HOME_COPY.remainingUsage.after}`,
      ),
    ).toBeVisible();

    await expect(page.getByTestId('diary-generation-skeleton')).toBeHidden({
      timeout: 12_000,
    });
    await expect(page.getByText(GENERATED_DIARY_TITLE)).toBeVisible();
    await expect(
      page.getByText(
        `${HOME_COPY.remainingUsage.before}2${HOME_COPY.remainingUsage.after}`,
      ),
    ).toBeVisible();
  });

  test('홈으로 이동한 뒤 생성이 실패하면 오류를 안내한다', async ({ page }) => {
    await goToDiaryWritePage(page, { generationFailure: true });
    await submitDiary(page);

    await expect(page).toHaveURL('/diary-generating');
    const dialogPromise = page.waitForEvent('dialog');
    await page.getByRole('button', { name: '뒤로 가기' }).click();

    await expect(page).toHaveURL('/');

    const dialog = await dialogPromise;
    expect(dialog.message()).toBe(GENERATION_ERROR_MESSAGE);
    await dialog.accept();

    await expect(page.getByText(GENERATED_DIARY_TITLE)).toHaveCount(0);
  });
});
