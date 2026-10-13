import { expect, test } from '@playwright/test';
import { AUTHENTICATED_STORAGE_STATE } from './auth';

test.use({ storageState: AUTHENTICATED_STORAGE_STATE });

test.describe('피드 URL 정렬', () => {
  for (const [query, sort] of [
    ['', 'LATEST'],
    ['?sort=invalid', 'LATEST'],
    ['?sort=POPULAR', 'POPULAR'],
  ] as const) {
    test(`${query || '기본 URL'}의 정렬값을 표시한다`, async ({ page }) => {
      await page.goto(`/${query}`);
      await expect(page.getByRole('combobox')).toHaveValue(sort);
    });
  }

  test('정렬·카테고리 변경이 URL 조건을 보존하고 뒤로 가기로 복원된다', async ({
    page,
  }) => {
    await page.goto('/?categoryId=1&other=keep');
    const select = page.getByRole('combobox');
    await expect(select).toHaveValue('LATEST');
    await select.selectOption('POPULAR');
    await expect(page).toHaveURL(/sort=POPULAR/);
    expect(new URL(page.url()).searchParams.get('categoryId')).toBe('1');
    expect(new URL(page.url()).searchParams.get('other')).toBe('keep');
    await expect(select.locator('option:checked')).toHaveText('인기순');

    await page.goBack();
    await expect(select).toHaveValue('LATEST');
    await expect(page).toHaveURL(/categoryId=1&other=keep$/);

    await select.selectOption('POPULAR');
    await expect(page).toHaveURL(/sort=POPULAR/);
    await page.getByRole('button', { name: '우테코', exact: true }).click();
    await expect(page).toHaveURL(/categoryId=2/);
    await expect(
      page.getByRole('button', { name: '우테코', exact: true }),
    ).toHaveCSS('font-weight', '500');
    expect(new URL(page.url()).searchParams.get('sort')).toBe('POPULAR');
    await select.selectOption('LATEST');
    await expect(select).toHaveValue('LATEST');
    await expect
      .poll(() => new URL(page.url()).searchParams.has('sort'))
      .toBe(false);
    expect(new URL(page.url()).searchParams.get('categoryId')).toBe('2');
    expect(new URL(page.url()).searchParams.get('other')).toBe('keep');
  });
});
