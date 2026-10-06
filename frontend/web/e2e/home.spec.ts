import { HOME_COPY } from '../src/pages/home/copy';
import { ERROR_MESSAGES } from '../src/shared/errorMessage';
import { expect, test, type Page } from '@playwright/test';
import { AUTHENTICATED_STORAGE_STATE } from './auth';
import {
  MOCK_SCENARIO_HEADER,
  MOCK_SCENARIOS,
} from '../src/mocks/mockScenarios';

test.use({ storageState: AUTHENTICATED_STORAGE_STATE });

const goToHomeAt = async (page: Page, date: string) => {
  await page.clock.setFixedTime(new Date(date));
  await page.goto('/');
};

const getDiaryItems = (page: Page) => {
  return page.getByRole('button', { name: /네컷만화 \d{4}-\d{2}-/ });
};

test.describe('월별 일기 조회', () => {
  for (const yearMonth of ['2026-08', '2026-10', '2027-01']) {
    test(`${yearMonth}에 홈 화면에 처음 진입하면 현재 월의 일기와 총 개수를 보여준다`, async ({
      page,
    }) => {
      await goToHomeAt(page, `${yearMonth}-04T12:00:00+09:00`);
      const loadingSpinner = page.getByRole('img', { name: '로딩 중' });

      await expect(loadingSpinner).toBeVisible();
      await expect(page.getByLabel('조회할 월')).toHaveValue(yearMonth);
      await expect(
        page.getByText(
          `${HOME_COPY.monthlyCount.before}6${HOME_COPY.monthlyCount.after}`,
        ),
      ).toBeVisible();
      await expect(loadingSpinner).toBeHidden();
      await expect(getDiaryItems(page)).toHaveCount(6);
      await expect(
        page.getByText('비가 와도, 나는 괜찮았다.', { exact: true }),
      ).toBeVisible();
      await page
        .getByRole('button', { name: /비가 와도, 나는 괜찮았다\./ })
        .click();
      await expect(page.getByText(`${yearMonth}-12`)).toBeVisible();
      const image = page.getByRole('img', { name: '네컷만화' });
      await expect(image).toHaveAttribute('src', /\.webp$/);
      await expect
        .poll(() => image.evaluate((element) => element.naturalWidth))
        .toBe(960);
    });
  }

  test('월별 일기 조회 실패 응답이 JSON이 아니면 에러 화면과 안내 메시지를 보여준다', async ({
    page,
  }) => {
    await page.setExtraHTTPHeaders({
      [MOCK_SCENARIO_HEADER]: MOCK_SCENARIOS.monthlyDiariesNonJsonError,
    });
    await goToHomeAt(page, '2026-08-30T12:00:00+09:00');

    const errorScreen = page.getByRole('alert');

    await expect(errorScreen).toBeVisible();
    await expect(
      errorScreen.getByRole('heading', {
        name: HOME_COPY.loadErrorTitle,
      }),
    ).toBeVisible();
    await expect(
      errorScreen.getByText(ERROR_MESSAGES.MONTHLY_DIARIES_FETCH_FAILED, {
        exact: true,
      }),
    ).toBeVisible();
    await expect(
      errorScreen.getByRole('button', { name: HOME_COPY.reloadAction }),
    ).toBeVisible();
  });

  test('다른 연도와 월을 선택하면 선택한 연도와 월의 일기와 총 개수를 보여준다', async ({
    page,
  }) => {
    await goToHomeAt(page, '2026-08-30T12:00:00+09:00');
    const monthInput = page.getByLabel('조회할 월');

    await expect(getDiaryItems(page)).toHaveCount(6);
    await monthInput.fill('2026-07');
    await expect(
      page.getByText(HOME_COPY.emptyOtherMonth(2026, 7)),
    ).toBeVisible();
    await monthInput.fill('2026-08');

    await expect(monthInput).toHaveValue('2026-08');
    await expect(
      page.getByText(
        `${HOME_COPY.monthlyCount.before}6${HOME_COPY.monthlyCount.after}`,
      ),
    ).toBeVisible();
    await expect(getDiaryItems(page)).toHaveCount(6);
  });

  test('선택한 연도와 월에 일기가 없으면 기록 없음 화면을 보여준다', async ({
    page,
  }) => {
    await goToHomeAt(page, '2026-08-30T12:00:00+09:00');
    const monthInput = page.getByLabel('조회할 월');

    await expect(getDiaryItems(page)).toHaveCount(6);
    await monthInput.fill('2025-12');

    await expect(monthInput).toHaveValue('2025-12');
    await expect(
      page.getByText(HOME_COPY.emptyOtherMonth(2025, 12)),
    ).toBeVisible();
    await expect(page.getByText(HOME_COPY.emptyDescription)).toBeVisible();
    await expect(
      page.getByRole('button', { name: HOME_COPY.createAction }),
    ).toBeVisible();
  });
});

test.describe('남은 일기 생성량 조회', () => {
  test('홈 화면에 처음 진입하면 오늘 남은 일기 생성량을 보여준다', async ({
    page,
  }) => {
    await goToHomeAt(page, '2026-08-30T12:00:00+09:00');

    await expect(
      page.getByText(
        `${HOME_COPY.remainingUsage.before}3${HOME_COPY.remainingUsage.after}`,
      ),
    ).toBeVisible();
  });
});

test.describe('홈 카드', () => {
  test('홈 화면에서 연속 기록과 공유 안내 카드를 숨긴다', async ({ page }) => {
    await goToHomeAt(page, '2026-08-30T12:00:00+09:00');
    await expect(page.getByRole('region', { name: '연속 기록' })).toHaveCount(
      0,
    );
    await expect(
      page.getByRole('region', { name: '이야기 공유 안내' }),
    ).toHaveCount(0);
  });
});
