import { DIARY_WRITE_COPY } from '../src/pages/diary-write/copy';
import { ALBUM_COPY } from '../src/pages/album/copy';
import { DIARY_GENERATION_FAILURE_MESSAGES } from '../src/shared/errorMessage';
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
const [GENERATION_ERROR_MESSAGE] =
  DIARY_GENERATION_FAILURE_MESSAGES.AI_PROVIDER_ERROR;

const goToDiaryWritePage = async (
  page: Page,
  options: { generationFailure?: boolean } = {},
): Promise<void> => {
  await page.clock.install({ time: TODAY });

  if (options.generationFailure) {
    await page.setExtraHTTPHeaders({
      [MOCK_SCENARIO_HEADER]: MOCK_SCENARIOS.diaryGenerationFailure,
    });
  }

  await page.goto('/diary-write');
};

const submitDiary = async (
  page: Page,
  content = VALID_DIARY_CONTENT,
): Promise<void> => {
  await page.getByRole('textbox').fill(content);
  await page.locator('form').getByRole('button').click();
};

const expectRemainingCount = async (
  page: Page,
  count: number,
): Promise<void> => {
  await expect(
    page.getByText(
      `${ALBUM_COPY.remainingUsage.before}${count}${ALBUM_COPY.remainingUsage.after}`,
    ),
  ).toBeVisible();
};

test.describe('일기 생성', (): void => {
  test('생성을 접수하면 앨범으로 이동하고 완료 토스트에서 결과를 볼 수 있다', async ({
    page,
  }): Promise<void> => {
    await goToDiaryWritePage(page);
    await submitDiary(page);

    await expect(page).toHaveURL('/album');
    await expect(page.getByText('네컷만화를 만들고 있어요')).toBeVisible();
    await page.clock.fastForward(10_000);
    await expect(page.getByText('네컷만화가 완성됐어요!')).toBeVisible();
    await page.getByRole('button', { name: '보러가기' }).click();

    await expect(page).toHaveURL(/\/diary\/[0-9a-f-]+$/);
    await expect(page.getByText(GENERATED_DIARY_TITLE)).toBeVisible();
    await expect(page.getByText('2026-08-30')).toBeVisible();
    await expect(page.getByRole('img', { name: '네컷만화' })).toBeVisible();
    await expect(page.getByText(VALID_DIARY_CONTENT)).toBeVisible();
  });

  test('10자 미만의 일기는 제출할 수 없다', async ({ page }): Promise<void> => {
    await goToDiaryWritePage(page);
    await submitDiary(page, '123456789');

    await expect(page.getByText(DIARY_WRITE_COPY.minLengthError)).toBeVisible();
    await expect(page).toHaveURL('/diary-write');
    await expect(page.getByRole('textbox')).toHaveValue('123456789');
  });

  test('생성에 실패하면 토스트에서 작성 화면으로 돌아가 내용을 다시 작성할 수 있다', async ({
    page,
  }): Promise<void> => {
    await goToDiaryWritePage(page, { generationFailure: true });
    await submitDiary(page);

    await expect(page).toHaveURL('/album');
    await expect(page.getByText('네컷만화를 만들고 있어요')).toBeVisible();
    await page.clock.fastForward(10_000);
    await expect(page.getByText(GENERATION_ERROR_MESSAGE)).toBeVisible();
    await page.getByRole('button', { name: '다시 작성하기' }).click();

    await expect(page).toHaveURL('/diary-write');
    await expect(page.getByRole('textbox')).toHaveValue(VALID_DIARY_CONTENT);
  });

  test('다른 화면에서 생성이 완료되어도 앨범 목록과 남은 횟수에 반영된다', async ({
    page,
  }): Promise<void> => {
    await goToDiaryWritePage(page);
    await submitDiary(page);

    await expect(page).toHaveURL('/album');
    await expect(page.getByTestId('diary-generation-skeleton')).toBeVisible();
    await expectRemainingCount(page, 2);
    await page.getByRole('button', { name: '설정', exact: true }).click();

    await expect(page).toHaveURL('/setting');
    await page.clock.fastForward(10_000);
    await expect(page.getByText('네컷만화가 완성됐어요!')).toBeVisible();
    await page
      .getByRole('button', { name: '네컷 모아보기', exact: true })
      .click();

    await expect(page).toHaveURL('/album');
    await expect(page.getByTestId('diary-generation-skeleton')).toBeHidden();
    await expect(page.getByText(GENERATED_DIARY_TITLE)).toBeVisible();
    await expectRemainingCount(page, 2);
  });

  test('앨범에서 생성이 실패하면 오류를 안내하고 남은 횟수를 다시 조회한다', async ({
    page,
  }): Promise<void> => {
    await goToDiaryWritePage(page, { generationFailure: true });
    await submitDiary(page);

    await expect(page).toHaveURL('/album');
    await expectRemainingCount(page, 2);
    await page.clock.fastForward(10_000);

    await expect(page.getByText(GENERATION_ERROR_MESSAGE)).toBeVisible();
    await expect(page.getByTestId('diary-generation-skeleton')).toBeHidden();
    await expect(page.getByText(GENERATED_DIARY_TITLE)).toHaveCount(0);
    await expectRemainingCount(page, 3);
  });
});
