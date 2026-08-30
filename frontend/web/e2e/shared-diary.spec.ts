import { expect, test, type Page } from '@playwright/test';

const SAMPLE_SHARE_ID = '06ed972e-0b79-4da0-9716-c9bd8faec85d';
const UNKNOWN_SHARE_ID = '00000000-0000-4000-8000-000000000999';
const SHARED_DIARY_TITLE = '비가 와도, 나는 괜찮았다.';

const goToSharedDiary = async (page: Page) => {
  await page.goto(`/shares/${SAMPLE_SHARE_ID}`);
};

test.describe('공유된 일기 조회', () => {
  test('공유 링크에 접속하면 로딩 후 일기를 보여준다', async ({ page }) => {
    await goToSharedDiary(page);
    const loadingSpinner = page.getByRole('img', { name: '로딩 중' });

    await expect(loadingSpinner).toBeVisible();
    await expect(
      page.getByText(SHARED_DIARY_TITLE, { exact: true }),
    ).toBeVisible();
    await expect(page.getByText('2026-08-12')).toBeVisible();
    await expect(
      page.getByRole('img', { name: SHARED_DIARY_TITLE }),
    ).toBeVisible();
    await expect(loadingSpinner).toBeHidden();
  });

  test('공유된 일기 조회에 실패하면 오류 메시지를 보여준다', async ({
    page,
  }) => {
    await page.goto(`/shares/${UNKNOWN_SHARE_ID}`);

    await expect(page.getByText('공유 링크를 찾을 수 없습니다.')).toBeVisible();
    await expect(
      page.getByText(SHARED_DIARY_TITLE, { exact: true }),
    ).toHaveCount(0);
  });

  test('하루들 로고를 누르면 홈 화면으로 이동한다', async ({ page }) => {
    await goToSharedDiary(page);
    await expect(
      page.getByText(SHARED_DIARY_TITLE, { exact: true }),
    ).toBeVisible();

    await page.getByRole('button', { name: '하루들' }).click();

    await expect(page).toHaveURL('/');
    await expect(page.getByLabel('조회할 월')).toBeVisible();
  });
});
