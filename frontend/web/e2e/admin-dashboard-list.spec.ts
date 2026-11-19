import { expect, test, type Locator, type Page } from '@playwright/test';
import { AUTHENTICATED_STORAGE_STATE } from './auth';

test.use({
  viewport: { width: 1600, height: 1000 },
  storageState: async ({ baseURL }, provideStorage): Promise<void> => {
    if (!baseURL) throw new Error('브라우저 테스트 주소가 없습니다.');
    await provideStorage({
      ...AUTHENTICATED_STORAGE_STATE,
      origins: AUTHENTICATED_STORAGE_STATE.origins.map((entry) => ({
        ...entry,
        origin: new URL(baseURL).origin,
      })),
    });
  },
});

const localDate = (daysFromToday = 0): string => {
  const date = new Date();
  date.setDate(date.getDate() + daysFromToday);
  const offset = date.getTimezoneOffset() * 60_000;
  return new Date(date.getTime() - offset).toISOString().slice(0, 10);
};

test('사용자 목록을 다음 쪽으로 넘기면 다른 사용자와 전체 수, 가입일을 보여 준다', async ({
  page,
}): Promise<void> => {
  await page.goto('/admin?view=users');
  await expect(page.getByText('총 45명')).toBeVisible();
  await expect(
    page.getByRole('columnheader', { name: '가입일' }),
  ).toBeVisible();
  await expect(
    page.getByRole('button', { name: '네오 상세 보기' }),
  ).toBeVisible();
  await expect(
    page.getByRole('button', { name: '이전 페이지' }),
  ).toBeDisabled();

  await page.getByRole('button', { name: '다음 페이지' }).click();

  await expect(page).toHaveURL(/usersPage=1/);
  await expect(page.getByText('2쪽')).toBeVisible();
  await expect(
    page.getByRole('button', { name: '사용자 21 상세 보기' }),
  ).toBeVisible();
  await expect(
    page.getByRole('button', { name: '네오 상세 보기' }),
  ).toHaveCount(0);
  await expect(page.getByText('총 45명')).toBeVisible();
});

test('생성 이력을 다음 쪽으로 넘긴 뒤 날짜를 바꾸면 첫 쪽부터 다시 보여 준다', async ({
  page,
}): Promise<void> => {
  await page.goto('/admin?view=generations');
  await expect(page.getByText('총 33건')).toBeVisible();
  await expect(
    page.getByText('6a2fbcda-943d-4014-a8e3-246377482e00'),
  ).toBeVisible();

  await page.getByRole('button', { name: '다음 페이지' }).click();

  await expect(page).toHaveURL(/historyPage=1/);
  await expect(
    page.getByText('50000000-0000-4000-8000-000000000018'),
  ).toBeVisible();
  await expect(
    page.getByText('6a2fbcda-943d-4014-a8e3-246377482e00'),
  ).toHaveCount(0);

  await page.getByRole('button', { name: '이전 날짜' }).click();

  await expect(page).not.toHaveURL(/historyPage=/);
  await expect(page.getByText('1쪽')).toBeVisible();
  await expect(
    page.getByText('6a2fbcda-943d-4014-a8e3-246377482e00'),
  ).toBeVisible();
});

test('날짜 이동 버튼은 요청 날짜를 바꾸고 사용자 목록은 다시 조회하지 않는다', async ({
  page,
}): Promise<void> => {
  const historyDates: string[] = [];
  let userListRequests = 0;
  page.on('request', (request): void => {
    const url = new URL(request.url());
    if (url.pathname === '/api/v1/admin/users') userListRequests += 1;
    if (
      url.pathname === '/api/v1/admin/generations' &&
      url.searchParams.get('status') !== 'FAILED'
    )
      historyDates.push(
        `${url.searchParams.get('from')}~${url.searchParams.get('to')}`,
      );
  });
  await page.goto('/admin');
  await expect(page.getByText('총 45명')).toBeVisible();
  await expect(page.getByText('총 33건')).toBeVisible();
  const usersBeforeDateChange = userListRequests;

  const previousDay = page.getByRole('button', { name: '이전 날짜' });
  const nextDay = page.getByRole('button', { name: '다음 날짜' });
  const todayButton = page.getByRole('button', { name: '오늘', exact: true });
  const dateInput = page.getByLabel('조회 날짜');

  await expect(dateInput).toHaveValue(localDate());
  await expect(nextDay).toBeDisabled();
  await expect(todayButton).toBeDisabled();

  await previousDay.click();
  await previousDay.click();
  await expect(dateInput).toHaveValue(localDate(-2));
  await expect
    .poll(() => historyDates.at(-1))
    .toBe(`${localDate(-2)}~${localDate(-2)}`);
  await expect(nextDay).toBeEnabled();
  await expect(todayButton).toBeEnabled();

  await nextDay.click();
  await expect(dateInput).toHaveValue(localDate(-1));
  await expect
    .poll(() => historyDates.at(-1))
    .toBe(`${localDate(-1)}~${localDate(-1)}`);

  await todayButton.click();
  await expect(dateInput).toHaveValue(localDate());
  await expect
    .poll(() => historyDates.at(-1))
    .toBe(`${localDate()}~${localDate()}`);
  await expect(nextDay).toBeDisabled();
  await expect(todayButton).toBeDisabled();

  expect(userListRequests).toBe(usersBeforeDateChange);
});

const detailNickname = (scope: Page | Locator): Locator =>
  scope
    .locator('dt', { hasText: '닉네임' })
    .locator('xpath=following-sibling::dd[1]');

test('사용자 상세에 닉네임을 보여 주고 없으면 -로 표시한다', async ({
  page,
}): Promise<void> => {
  await page.goto('/admin?view=users');
  await page.getByRole('button', { name: '네오 상세 보기' }).click();
  await expect(detailNickname(page)).toHaveText('SunnyHaru01');

  await page.getByRole('button', { name: '사용자 04 상세 보기' }).click();
  await expect(
    page.getByText('사용자 04', { exact: true }).and(page.locator('dd')),
  ).toBeVisible();
  await expect(detailNickname(page)).toHaveText('-');
});

test('긴 목록의 아래쪽 사용자를 골라도 상세가 화면 안에 보인다', async ({
  page,
}): Promise<void> => {
  await page.goto('/admin?view=users');
  await page.getByRole('button', { name: '사용자 20 상세 보기' }).click();

  await expect(
    page.getByRole('heading', { name: '사용자 상세' }),
  ).toBeInViewport();
  await expect(
    page.getByText('사용자 20', { exact: true }).and(page.locator('dd')),
  ).toBeInViewport();
});

test.describe('좁은 화면', (): void => {
  test.use({ viewport: { width: 1200, height: 900 } });

  test('사용자를 고르면 상세를 모달로 열고 사용량을 바로 변경한다', async ({
    page,
  }): Promise<void> => {
    await page.goto('/admin?view=users');
    await page.getByRole('button', { name: '네오 상세 보기' }).click();

    const detail = page.locator('dialog[open]');
    await expect(
      detail.getByRole('heading', { name: '사용자 상세' }),
    ).toBeVisible();
    await expect(
      detail.getByText('네오', { exact: true }).and(page.locator('dd')),
    ).toBeVisible();
    await expect(detailNickname(detail)).toHaveText('SunnyHaru01');

    await detail.getByRole('button', { name: '사용 횟수 복구' }).click();
    const usageDialog = page.getByRole('dialog', { name: '사용 횟수 복구' });
    await expect(usageDialog).toBeVisible();
    await usageDialog.getByRole('button', { name: '변경하기' }).click();

    await expect(usageDialog).toHaveCount(0);
    await expect(
      detail.getByText('남은 횟수').locator('xpath=..'),
    ).toContainText('3');
  });
});

test('대시보드에서 탈퇴 회원을 뺀 전체 회원 수를 보여 준다', async ({
  page,
}): Promise<void> => {
  await page.goto('/admin');
  await expect(
    page.getByRole('region', { name: '전체 회원 수' }).getByText('39명'),
  ).toBeVisible();
});

test('대시보드에서 같은 통계 응답으로 오늘 가입자 수를 보여 준다', async ({
  page,
}): Promise<void> => {
  await page.goto('/admin');
  await expect(
    page.getByRole('region', { name: '오늘 가입자 수' }).getByText('3명'),
  ).toBeVisible();
  await expect(
    page.getByRole('region', { name: '전체 회원 수' }).getByText('39명'),
  ).toBeVisible();
});

const firstUserNames = async (
  page: import('@playwright/test').Page,
  count: number,
): Promise<string[]> =>
  (await page.locator('tbody tr td:first-child').allInnerTexts())
    .slice(0, count)
    .map((name) => name.trim());

test('사용자 목록에 닉네임과 생성한 일기 수를 보여 주고 닉네임으로 검색한다', async ({
  page,
}): Promise<void> => {
  await page.goto('/admin?view=users');
  const neoRow = page.getByRole('row').filter({
    has: page.getByRole('button', { name: '네오 상세 보기' }),
  });
  await expect(neoRow.getByRole('cell').nth(1)).toHaveText('SunnyHaru01');
  await expect(neoRow.getByRole('cell').nth(5)).toHaveText('0');
  const noNicknameRow = page.getByRole('row').filter({
    has: page.getByRole('button', { name: '사용자 04 상세 보기' }),
  });
  await expect(noNicknameRow.getByRole('cell').nth(1)).toHaveText('-');
  await expect(noNicknameRow.getByRole('cell').nth(5)).toHaveText('21');

  await page
    .getByPlaceholder('이름, 닉네임 또는 사용자 ID 검색')
    .fill('moonwalk08');
  await page.getByRole('button', { name: '검색' }).click();

  await expect(page.getByText('총 1명')).toBeVisible();
  await expect(
    page.getByRole('button', { name: '사용자 08 상세 보기' }),
  ).toBeVisible();
  await expect(page).toHaveURL(/query=moonwalk08/);
});

test('정렬을 고르면 정렬 값을 보내고 첫 쪽부터 다시 보여 준다', async ({
  page,
}): Promise<void> => {
  const userRequests: string[] = [];
  page.on('request', (request): void => {
    const url = new URL(request.url());
    if (url.pathname === '/api/v1/admin/users')
      userRequests.push(
        `${url.searchParams.get('sort')} ${url.searchParams.get('direction')} ${url.searchParams.get('page')}`,
      );
  });
  await page.goto('/admin?view=users&usersPage=1');
  await expect(page.getByText('2쪽')).toBeVisible();
  const sortKey = page.getByRole('combobox', { name: '정렬 기준' });
  const sortDirection = page.getByRole('combobox', { name: '정렬 방향' });

  await sortKey.selectOption({ label: '사전순' });
  await expect(page.getByText('1쪽')).toBeVisible();
  await expect(page).not.toHaveURL(/usersPage=/);
  await expect(sortDirection).toHaveValue('ASC');
  await expect.poll(() => userRequests.at(-1)).toBe('NAME ASC 0');
  await expect
    .poll(() => firstUserNames(page, 2))
    .toEqual(['네오', '사용자 02']);

  await sortDirection.selectOption({ label: '내림차순' });
  await expect.poll(() => userRequests.at(-1)).toBe('NAME DESC 0');
  await expect
    .poll(() => firstUserNames(page, 2))
    .toEqual(['사용자 45', '사용자 44']);

  await sortDirection.selectOption({ label: '오름차순' });
  await sortKey.selectOption({ label: '오늘 남은 횟수' });
  await expect(sortDirection).toHaveValue('DESC');
  await expect.poll(() => userRequests.at(-1)).toBe('REMAINING_COUNT DESC 0');

  await sortKey.selectOption({ label: '가입일' });
  await expect(sortDirection).toHaveValue('DESC');
  await expect.poll(() => userRequests.at(-1)).toBe('CREATED_AT DESC 0');
  await expect(page).not.toHaveURL(/direction=/);
  await expect(page).not.toHaveURL(/sort=/);
});

test('새로고침해도 정렬, 검색어, 쪽 번호와 조회 날짜를 유지한다', async ({
  page,
}): Promise<void> => {
  await page.goto('/admin?view=users');
  await page
    .getByRole('combobox', { name: '정렬 기준' })
    .selectOption({ label: '사전순' });
  await page
    .getByRole('combobox', { name: '정렬 방향' })
    .selectOption({ label: '오름차순' });
  await page.getByRole('button', { name: '다음 페이지' }).click();
  await expect(page).toHaveURL(/usersPage=1/);

  await page.reload();

  await expect(page.getByRole('combobox', { name: '정렬 기준' })).toHaveValue(
    'NAME',
  );
  await expect(page.getByRole('combobox', { name: '정렬 방향' })).toHaveValue(
    'ASC',
  );
  await expect(page.getByText('2쪽')).toBeVisible();
  await expect.poll(() => firstUserNames(page, 1)).toEqual(['사용자 21']);

  const search = page.getByPlaceholder('이름, 닉네임 또는 사용자 ID 검색');
  await search.fill('SunnyHaru');
  await page.getByRole('button', { name: '검색' }).click();
  await expect(page.getByText('총 5명')).toBeVisible();
  await expect(page).not.toHaveURL(/usersPage=/);

  await page.reload();

  await expect(search).toHaveValue('SunnyHaru');
  await expect(page.getByText('총 5명')).toBeVisible();

  await page.goBack();
  await expect(search).toHaveValue('');
  await expect(page.getByText('2쪽')).toBeVisible();

  await page.goto('/admin?view=generations');
  await page.getByRole('button', { name: '이전 날짜' }).click();
  await expect(page).toHaveURL(new RegExp(`historyDate=${localDate(-1)}`));

  await page.reload();

  await expect(page.getByLabel('조회 날짜')).toHaveValue(localDate(-1));
  await page.getByRole('button', { name: '오늘', exact: true }).click();
  await expect(page).not.toHaveURL(/historyDate=/);
});

test('주소의 정렬 값이 잘못되면 기본 정렬로 보여 준다', async ({
  page,
}): Promise<void> => {
  const userRequests: string[] = [];
  page.on('request', (request): void => {
    const url = new URL(request.url());
    if (url.pathname === '/api/v1/admin/users')
      userRequests.push(
        `${url.searchParams.get('sort')} ${url.searchParams.get('direction')}`,
      );
  });
  await page.goto('/admin?view=users&sort=name&direction=up&usersPage=-3');

  await expect(page.getByText('총 45명')).toBeVisible();
  await expect(page.getByText('1쪽')).toBeVisible();
  // 개발 서버의 StrictMode가 첫 조회를 두 번 보내므로 보낸 값의 종류만 확인한다.
  expect([...new Set(userRequests)]).toEqual(['CREATED_AT DESC']);
  await expect(page.getByRole('combobox', { name: '정렬 기준' })).toHaveValue(
    'CREATED_AT',
  );
  await expect(page.getByRole('combobox', { name: '정렬 방향' })).toHaveValue(
    'DESC',
  );
  await expect.poll(() => firstUserNames(page, 1)).toEqual(['네오']);
});

test('정렬을 바꾸자마자 다음 페이지로 이동해도 정렬 조건을 잃지 않는다', async ({
  page,
}): Promise<void> => {
  await page.goto('/admin?view=users');
  await expect(page.getByText('1쪽')).toBeVisible();

  await page.evaluate((): void => {
    const sort = document.querySelector<HTMLSelectElement>(
      'select[aria-label="정렬 기준"]',
    );
    const next = Array.from(document.querySelectorAll('button')).find(
      (button) => button.textContent === '다음 페이지',
    );
    if (!sort || !next)
      throw new Error('정렬 선택이나 다음 페이지 버튼이 없습니다.');
    sort.value = 'NAME';
    sort.dispatchEvent(new Event('change', { bubbles: true }));
    next.click();
  });

  await expect(page).toHaveURL(/usersPage=1/);
  await expect(page).toHaveURL(/sort=NAME/);
  await expect(page.getByRole('combobox', { name: '정렬 기준' })).toHaveValue(
    'NAME',
  );
});
