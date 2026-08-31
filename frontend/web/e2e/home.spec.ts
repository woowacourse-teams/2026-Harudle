import { expect, test, type Page } from '@playwright/test';
import { AUTHENTICATED_STORAGE_STATE } from './auth';

test.use({ storageState: AUTHENTICATED_STORAGE_STATE });

const goToHomeAt = async (page: Page, date: string) => {
  await page.clock.setFixedTime(new Date(date));
  await page.goto('/');
};

const getDiaryItems = (page: Page) => {
  return page.getByRole('button', { name: /그림일기 2026-08-/ });
};

test.describe('월별 일기 조회', () => {
  test('홈 화면에 처음 진입하면 현재 연도와 월의 일기와 총 개수를 보여준다', async ({
    page,
  }) => {
    await goToHomeAt(page, '2026-08-30T12:00:00+09:00');
    const loadingSpinner = page.getByRole('img', { name: '로딩 중' });

    await expect(loadingSpinner).toBeVisible();
    await expect(page.getByLabel('조회할 월')).toHaveValue('2026-08');
    await expect(page.getByText('6개의 기록')).toBeVisible();
    await expect(loadingSpinner).toBeHidden();
    await expect(getDiaryItems(page)).toHaveCount(6);
    await expect(
      page.getByText('비가 와도, 나는 괜찮았다.', { exact: true }),
    ).toBeVisible();
  });

  test('다른 연도와 월을 선택하면 선택한 연도와 월의 일기와 총 개수를 보여준다', async ({
    page,
  }) => {
    await goToHomeAt(page, '2026-07-30T12:00:00+09:00');
    const monthInput = page.getByLabel('조회할 월');

    await expect(page.getByText('아직 기록이 없어요')).toBeVisible();
    await monthInput.fill('2026-08');

    await expect(monthInput).toHaveValue('2026-08');
    await expect(page.getByText('6개의 기록')).toBeVisible();
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
    await expect(page.getByText('0개의 기록')).toBeVisible();
    await expect(page.getByText('아직 기록이 없어요')).toBeVisible();
    await expect(
      page.getByText('오늘의 이야기를 네컷 만화로 남겨보세요!'),
    ).toBeVisible();
    await expect(
      page.getByRole('button', { name: '새 일기 쓰기' }),
    ).toBeVisible();
  });
});

test.describe('남은 일기 생성량 조회', () => {
  test('홈 화면에 처음 진입하면 오늘 남은 일기 생성량을 보여준다', async ({
    page,
  }) => {
    await goToHomeAt(page, '2026-08-30T12:00:00+09:00');

    await expect(page.getByText(/오늘 남은 생성\s*3\s*회/)).toBeVisible();
  });
});

test.describe('스트릭', () => {
  test('홈 화면에 처음 진입하면 연속 기록 일수를 보여준다', async ({
    page,
  }) => {
    await goToHomeAt(page, '2026-08-30T12:00:00+09:00');
    const streakCard = page.getByRole('region', { name: '연속 기록' });

    await expect(streakCard.getByText('6일째')).toBeVisible();
    await expect(
      streakCard.getByText('오늘도 기록을 이어갔어요!'),
    ).toBeVisible();
  });
});

test.describe('하단 네비게이션', () => {
  test('홈 화면에서 설정 화면으로 이동할 수 있다', async ({ page }) => {
    await goToHomeAt(page, '2026-08-30T12:00:00+09:00');

    await page.getByRole('button', { name: '설정' }).click();

    await expect(page).toHaveURL('/setting');
    await expect(page.getByText('피드백 남기기')).toBeVisible();
  });
});
