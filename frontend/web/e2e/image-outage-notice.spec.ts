import { expect, test } from '@playwright/test';

test.use({ storageState: { cookies: [], origins: [] } });

test('로그인 후 공지를 표시하고, 확인 후에는 배너로 다시 열 수 있다', async ({
  page,
}): Promise<void> => {
  await page.goto('/login');

  const notice = page.getByRole('dialog', {
    name: '일기 이미지 재생성 완료 및 지원 안내',
  });

  await expect(notice).toHaveCount(0);
  await page.getByRole('button', { name: '카카오로 시작하기' }).click();
  await expect(page).toHaveURL('/');
  await expect(notice).toBeVisible();
  await notice.getByRole('button', { name: '확인했어요' }).click();
  await expect(notice).toBeHidden();

  await page.reload();
  await expect(
    page.getByRole('button', {
      name: /일기 이미지 재생성 완료 및 지원 안내 자세히 보기/,
    }),
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

test('비로그인 랜딩에는 공지 배너와 팝업을 표시하지 않는다', async ({
  page,
}): Promise<void> => {
  await page.goto('/landing');
  await expect(page.getByRole('heading', { level: 1 })).toBeVisible();
  await expect(page.getByRole('dialog')).toHaveCount(0);
  await expect(
    page.getByRole('button', { name: /일기 이미지 재생성 완료 및 지원 안내/ }),
  ).toHaveCount(0);
});
