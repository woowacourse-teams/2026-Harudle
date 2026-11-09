import { expect, test } from '@playwright/test';
import { AUTHENTICATED_STORAGE_STATE } from './auth';

test.use({
  storageState: AUTHENTICATED_STORAGE_STATE,
  viewport: { width: 1280, height: 840 },
});

test.beforeEach(async ({ page }): Promise<void> => {
  await page.goto('/');
  await expect(
    page.getByRole('button', { name: '전체', exact: true }),
  ).toBeVisible();
});

test('마우스 휠로 숨겨진 카테고리까지 스크롤하고 선택한다', async ({
  page,
}): Promise<void> => {
  const firstCategory = page.getByRole('button', { name: '전체', exact: true });
  const categoryList = firstCategory.locator('..');
  const lastCategory = categoryList.getByRole('button', {
    name: '취미',
    exact: true,
  });

  await firstCategory.hover();
  await page.mouse.wheel(0, 600);
  await expect(lastCategory).toBeInViewport({ ratio: 1 });
  await expect(page).toHaveURL('/');

  await lastCategory.click();
  await expect(page).toHaveURL('/?categoryId=8');
});

test('기본 가로 스크롤과 키보드 선택을 지원한다', async ({
  page,
}): Promise<void> => {
  const firstCategory = page.getByRole('button', { name: '전체', exact: true });
  const categoryList = firstCategory.locator('..');

  const expectedScrollLeft = await categoryList.evaluate((element): number =>
    Math.min(150, element.scrollWidth - element.clientWidth),
  );
  await firstCategory.hover();
  await page.mouse.wheel(150, 0);
  await expect
    .poll((): Promise<number> =>
      categoryList.evaluate((element): number => element.scrollLeft),
    )
    .toBe(expectedScrollLeft);

  const category = categoryList.getByRole('button', {
    name: '일상',
    exact: true,
  });
  await category.press('Enter');
  await expect(page).toHaveURL('/?categoryId=1');
  await expect(category).toHaveCSS('font-weight', '500');

  await firstCategory.press('Enter');
  await expect(page).toHaveURL('/');
});

test('URL로 진입하거나 뒤로·앞으로 이동하면 선택된 카테고리가 보이도록 스크롤한다', async ({
  page,
}): Promise<void> => {
  await page.goto('/?categoryId=8');
  const firstCategory = page.getByRole('button', { name: '전체', exact: true });
  const categoryList = firstCategory.locator('..');
  const lastCategory = categoryList.getByRole('button', {
    name: '취미',
    exact: true,
  });

  await expect(lastCategory).toHaveCSS('font-weight', '500');
  await expect(lastCategory).toBeInViewport({ ratio: 1 });
  expect(
    await categoryList.evaluate((element): number => element.scrollLeft),
  ).toBeGreaterThan(0);

  await firstCategory.press('Enter');
  await expect(page).toHaveURL('/');
  await expect(firstCategory).toHaveCSS('font-weight', '500');
  await expect(firstCategory).toBeInViewport({ ratio: 1 });

  await page.goBack();
  await expect(page).toHaveURL('/?categoryId=8');
  await expect(lastCategory).toHaveCSS('font-weight', '500');
  await expect(lastCategory).toBeInViewport({ ratio: 1 });

  await page.goForward();
  await expect(page).toHaveURL('/');
  await expect(firstCategory).toHaveCSS('font-weight', '500');
  await expect(firstCategory).toBeInViewport({ ratio: 1 });
});
