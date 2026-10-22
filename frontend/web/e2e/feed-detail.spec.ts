import { expect, test } from '@playwright/test';
import { AUTHENTICATED_STORAGE_STATE } from './auth';

const detailPath = '/feeds/00000000-0000-4000-8000-000000000001';

test('상세 직접 진입과 새로고침에서 세션을 복원해 본인 관리 메뉴를 표시한다', async ({
  browser,
}): Promise<void> => {
  const context = await browser.newContext({
    storageState: AUTHENTICATED_STORAGE_STATE,
  });
  try {
    const page = await context.newPage();
    await page.addInitScript((): void => {
      localStorage.removeItem('harudle.has-seen-landing');
    });
    const detailAuthorizations: (string | undefined)[] = [];
    page.on('request', (request): void => {
      if (new URL(request.url()).pathname === `/api/v1${detailPath}`) {
        detailAuthorizations.push(request.headers().authorization);
      }
    });

    await page.goto(detailPath);
    await expect(
      page.getByRole('article').getByRole('button', { name: '피드 더보기' }),
    ).toBeVisible();
    await expect(page).toHaveURL(detailPath);
    await page.reload();
    await expect(
      page.getByRole('article').getByRole('button', { name: '피드 더보기' }),
    ).toBeVisible();
    await expect(page).toHaveURL(detailPath);
    await page.getByRole('button', { name: '피드 더보기' }).click();
    const menu = page.getByRole('dialog').filter({
      has: page.getByRole('heading', { name: '피드 관리', exact: true }),
    });
    await expect(
      menu.getByRole('button', { name: /피드 삭제$/ }),
    ).toBeVisible();
    await expect(menu.getByRole('button', { name: '피드 신고' })).toHaveCount(
      0,
    );
    await expect(menu.getByRole('button', { name: '작성자 신고' })).toHaveCount(
      0,
    );
    expect(detailAuthorizations.length).toBeGreaterThanOrEqual(2);
    expect(
      detailAuthorizations.every(
        (value): boolean => value === 'Bearer mock-access-token',
      ),
    ).toBe(true);
  } finally {
    await context.close();
  }
});

test('비회원도 공개 상세를 보고 피드·댓글 신고를 누르면 로그인 안내를 받는다', async ({
  page,
}): Promise<void> => {
  const reportRequests: string[] = [];
  page.on('request', (request): void => {
    if (
      request.method() === 'POST' &&
      new URL(request.url()).pathname === '/api/v1/reports'
    ) {
      reportRequests.push(request.url());
    }
  });
  await page.goto(detailPath);
  await expect(page.getByRole('article')).toBeVisible();
  await expect(page).toHaveURL(detailPath);
  await page
    .getByRole('article')
    .getByRole('button', { name: '피드 더보기' })
    .click();
  const reportMenu = page.getByRole('dialog').filter({
    has: page.getByRole('heading', { name: '신고하기', exact: true }),
  });
  await expect(reportMenu.getByRole('button', { name: /삭제$/ })).toHaveCount(
    0,
  );
  await reportMenu.getByRole('button', { name: '피드 신고' }).click();
  const login = page.getByRole('dialog').filter({
    has: page.getByRole('heading', {
      name: '로그인하고 계속할까요?',
      exact: true,
    }),
  });
  await expect(login).toContainText('신고를 접수하려면 로그인해 주세요.');
  await expect(
    login.getByRole('button', { name: '카카오로 로그인' }),
  ).toBeVisible();
  await login.getByRole('button', { name: '계속 둘러보기' }).click();
  await expect(login).not.toBeVisible();
  await expect(page).toHaveURL(detailPath);

  await page
    .getByRole('listitem')
    .first()
    .getByRole('button', { name: '댓글 더보기' })
    .click();
  await reportMenu.getByRole('button', { name: '댓글 신고' }).click();
  await expect(login).toContainText('신고를 접수하려면 로그인해 주세요.');
  await expect(page.getByRole('radio')).toHaveCount(0);
  await expect(page).toHaveURL(detailPath);
  expect(reportRequests).toHaveLength(0);
});

test.describe('로그인 회원의 상세 메뉴', (): void => {
  test.use({ storageState: AUTHENTICATED_STORAGE_STATE });

  test('본인 피드 삭제를 취소하면 유지하고 확인하면 삭제한다', async ({
    page,
  }): Promise<void> => {
    await page.goto(detailPath);
    const options = page
      .getByRole('article')
      .getByRole('button', { name: '피드 더보기' });
    const menu = page.getByRole('dialog').filter({
      has: page.getByRole('heading', { name: '피드 관리', exact: true }),
    });
    const confirm = page.getByRole('dialog').filter({
      has: page.getByRole('heading', {
        name: '이 피드를 삭제할까요?',
        exact: true,
      }),
    });
    await options.click();
    await menu.getByRole('button', { name: /피드 삭제$/ }).click();
    await confirm.getByRole('button', { name: '취소' }).click();
    await expect(confirm).not.toBeVisible();
    await expect(page.getByRole('article')).toBeVisible();
    await expect(page).toHaveURL(detailPath);

    await options.click();
    await menu.getByRole('button', { name: /피드 삭제$/ }).click();
    const deletion = page.waitForResponse(
      (response): boolean =>
        response.request().method() === 'DELETE' &&
        new URL(response.url()).pathname === `/api/v1${detailPath}`,
    );
    await confirm.getByRole('button', { name: /피드 삭제$/ }).click();
    expect((await deletion).status()).toBe(204);
    await expect(page).toHaveURL('/');
    await expect(confirm).not.toBeVisible();
  });

  for (const report of [
    {
      action: '댓글 신고',
      targetType: 'COMMENT',
      targetId: '10000000-0000-4000-8000-000000000002',
    },
    {
      action: '작성자 신고',
      targetType: 'USER',
      targetId: '00000000-0000-4000-8000-000000000100',
    },
  ] as const) {
    test(`타인 댓글의 ${report.action}가 선택한 대상으로 접수된다`, async ({
      page,
    }): Promise<void> => {
      await page.goto(detailPath);
      const comment = page
        .getByRole('listitem')
        .filter({ hasText: '마지막 장면 너무 공감돼요!' })
        .first();
      await comment.getByRole('button', { name: '댓글 더보기' }).click();
      const menu = page.getByRole('dialog').filter({
        has: page.getByRole('heading', { name: '신고하기', exact: true }),
      });
      await expect(
        menu.getByRole('button', { name: /댓글 삭제$/ }),
      ).toHaveCount(0);
      await menu.getByRole('button', { name: report.action }).click();
      const form = page.getByRole('dialog').filter({
        has: page.getByRole('heading', { name: report.action, exact: true }),
      });
      await form.getByRole('radio', { name: '괴롭힘·욕설' }).check();
      await form
        .getByRole('textbox', { name: '상세 내용 (선택)' })
        .fill('댓글에서 욕설을 반복해요.');
      const received = page.waitForResponse(
        (response): boolean =>
          response.request().method() === 'POST' &&
          new URL(response.url()).pathname === '/api/v1/reports',
      );
      await form
        .getByRole('button', { name: '신고 접수', exact: true })
        .click();
      const response = await received;
      expect(response.status()).toBe(201);
      const body: unknown = response.request().postDataJSON();
      expect(body).toEqual({
        targetType: report.targetType,
        targetId: report.targetId,
        reason: 'HARASSMENT',
        description: '댓글에서 욕설을 반복해요.',
      });
      expect(response.request().headers().authorization).toBe(
        'Bearer mock-access-token',
      );
      expect(response.request().headers()['x-xsrf-token']).toBe(
        'mock-csrf-token',
      );
      await expect(form.getByRole('status')).toHaveText(
        '신고가 접수되었어요. 운영자가 내용을 확인할게요.',
      );
    });
  }
});
