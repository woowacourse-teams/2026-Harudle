import { expect, test, type Page } from '@playwright/test';
import {
  MOCK_SCENARIO_HEADER,
  MOCK_SCENARIOS,
} from '../src/mocks/mockScenarios';

const TODAY = new Date('2026-08-30T12:00:00+09:00');
const VALID_DIARY_CONTENT =
  '오늘은 친구와 공원을 산책하며 즐거운 이야기를 나누었다.';
const GENERATED_DIARY_TITLE = '오늘 하루의 소중한 기록';
const GENERATION_ERROR_MESSAGE =
  '일기를 만드는 중 문제가 발생했습니다. 다시 시도해주세요.';

const generationStepMessages = [
  '오늘의 이야기를 차근차근 읽고 있어요',
  '기억에 남는 장면을 한 장면씩 적어보고 있어요',
  '네 장면을 고르고 이야기의 흐름을 맞추고 있어요',
  '색을 더하고 다듬어 네컷 만화를 완성하고 있어요',
] as const;

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
    await expect(page.getByText(generationStepMessages[0])).toBeVisible();

    for (const message of generationStepMessages.slice(1)) {
      await page.clock.fastForward(3_000);
      await expect(page.getByText(message)).toBeVisible();
    }

    await expect(
      page.getByText('완성했어요! 2초 뒤에 앨범으로 이동해요'),
    ).toBeVisible({
      timeout: 12_000,
    });
    await page.clock.fastForward(2_000);

    await expect(page).toHaveURL(/\/diary\/[0-9a-f-]+$/);
    await expect(page.getByText(GENERATED_DIARY_TITLE)).toBeVisible();
    await expect(page.getByText('2026-08-30')).toBeVisible();
    await expect(page.getByRole('img', { name: '그림 일기' })).toBeVisible();
    await expect(page.getByText(VALID_DIARY_CONTENT)).toBeVisible();
  });

  test('10자 미만의 일기는 제출할 수 없다', async ({ page }) => {
    await goToDiaryWritePage(page);
    await submitDiary(page, '123456789');

    await expect(page.getByText('10자 이상으로 입력해주세요!')).toBeVisible();
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
      errorPage.getByText('일기 생성 중 오류가 발생했어요'),
    ).toBeVisible();
    await expect(errorPage.getByText(GENERATION_ERROR_MESSAGE)).toBeVisible();

    await page.getByRole('button', { name: '다시 작성하기' }).click();

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
    await expect(page.getByText(/오늘 남은 생성\s*3\s*회/)).toBeVisible();

    await expect(page.getByTestId('diary-generation-skeleton')).toBeHidden({
      timeout: 12_000,
    });
    await expect(page.getByText(GENERATED_DIARY_TITLE)).toBeVisible();
    await expect(page.getByText(/오늘 남은 생성\s*2\s*회/)).toBeVisible();
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
