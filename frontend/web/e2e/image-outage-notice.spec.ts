import { expect, test } from '@playwright/test';

test.use({ storageState: { cookies: [], origins: [] } });

test('처음 방문하면 공지를 표시하고, 확인 후에는 배너로 다시 열 수 있다', async ({
  page,
}): Promise<void> => {
  await page.goto('/login');

  const notice = page.getByRole('dialog', {
    name: '일기 이미지 재생성 완료 및 지원 안내',
  });

  await expect(notice).toBeVisible();
  await notice.getByRole('button', { name: '확인했어요' }).click();
  await expect(notice).toBeHidden();

  await page.reload();
  await expect(
    page.getByRole('button', { name: '카카오로 시작하기' }),
  ).toBeVisible();
  await expect(notice).toBeHidden();

  await page
    .getByRole('button', {
      name: '일기 이미지 재생성 완료 및 지원 안내 자세히 보기',
    })
    .click();
  await expect(notice).toBeVisible();
  await notice.getByRole('button', { name: '확인했어요' }).click();
  await expect(notice).toBeHidden();
});
