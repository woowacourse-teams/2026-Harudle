import { FEED_DETAIL_COPY } from '../src/pages/feed-detail/copy';
import {
  MOCK_SCENARIO_HEADER,
  MOCK_SCENARIOS,
} from '../src/mocks/mockScenarios';
import { ERROR_MESSAGES } from '../src/shared/errorMessage';
import { expect, test } from '@playwright/test';
import { AUTHENTICATED_STORAGE_STATE } from './auth';

const detailPath = '/feeds/00000000-0000-4000-8000-000000000001';

test.describe('피드 상세 뒤로가기', (): void => {
  test.use({ storageState: AUTHENTICATED_STORAGE_STATE });

  test('필터가 적용된 목록에서 진입하면 상세를 새로고침해도 이전 목록으로 돌아간다', async ({
    page,
  }): Promise<void> => {
    const feedPath = '/?categoryId=1&sort=POPULAR';
    await page.goto(feedPath);
    await expect(page.getByRole('article')).toHaveCount(3);
    await page
      .getByRole('article')
      .first()
      .getByRole('button', { name: /^\d+$/ })
      .nth(1)
      .click();
    await expect(page).toHaveURL(/\/feeds\/[^/]+$/);
    await expect(
      page.getByRole('button', { name: '뒤로가기 아이콘' }),
    ).toBeVisible();

    await page.reload();
    await page.getByRole('button', { name: '뒤로가기 아이콘' }).click();

    await expect(page).toHaveURL(feedPath);
    await expect(page.getByRole('article')).toHaveCount(3);
    await expect(page.getByRole('combobox')).toHaveValue('POPULAR');
  });

  test('외부 공유 링크로 진입하면 헤더 뒤로가기는 홈으로 이동하고 상세 기록을 교체한다', async ({
    page,
  }): Promise<void> => {
    const externalUrl = 'https://external.test/';
    await page.route(externalUrl, async (route): Promise<void> => {
      await route.fulfill({
        contentType: 'text/html; charset=utf-8',
        body: `<a href="http://localhost:5173${detailPath}">공유 피드 보기</a>`,
      });
    });
    await page.goto(externalUrl);
    await page.getByRole('link', { name: '공유 피드 보기' }).click();
    await expect(page).toHaveURL(detailPath);

    await page.getByRole('button', { name: '뒤로가기 아이콘' }).click();

    await expect(page).toHaveURL('/');
    await expect(page.getByRole('article')).toHaveCount(20);
    await page.goBack();
    await expect(page).toHaveURL(externalUrl);
  });
});

test('상세 직접 진입과 새로고침에서 세션을 복원해 본인 피드를 표시한다', async ({
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
      page
        .getByRole('article')
        .getByRole('button', { name: '옵션 더보기 아이콘' }),
    ).toBeVisible();
    await expect(page).toHaveURL(detailPath);
    await page.reload();
    await expect(
      page
        .getByRole('article')
        .getByRole('button', { name: '옵션 더보기 아이콘' }),
    ).toBeVisible();
    await expect(page).toHaveURL(detailPath);
    await page
      .getByRole('article')
      .getByRole('button', { name: '옵션 더보기 아이콘' })
      .click();
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
  await expect(page.locator('input[type="radio"]')).toHaveCount(0);
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

  test('신고 응답 대기 중에는 닫기를 막고 완료 후 다른 대상을 선택할 수 있다', async ({
    page,
  }): Promise<void> => {
    await page.goto(detailPath);
    const comment = page
      .getByRole('listitem')
      .filter({ hasText: '마지막 장면 너무 공감돼요!' })
      .first();
    await expect(comment).toBeVisible();
    await expect(page.locator('input[type="radio"]')).toHaveCount(0);
    // MSW가 처리하는 실제 fetch를 보존하고 첫 신고만 명시적으로 대기시킨다.
    await page.evaluate((): void => {
      const fetch = window.fetch.bind(window);
      let waitForReport = true;
      window.fetch = async (info, init): Promise<Response> => {
        const url =
          typeof info === 'string'
            ? info
            : info instanceof URL
              ? info.href
              : info.url;
        if (
          waitForReport &&
          init?.method === 'POST' &&
          new URL(url, window.location.href).pathname === '/api/v1/reports'
        ) {
          waitForReport = false;
          await new Promise<void>((resolve): void => {
            window.addEventListener('release-report', (): void => resolve(), {
              once: true,
            });
            document.documentElement.dataset.reportWaiting = 'true';
          });
          delete document.documentElement.dataset.reportWaiting;
        }
        return fetch(info, init);
      };
    });

    await comment.getByRole('button', { name: '댓글 더보기' }).click();
    const menu = page.getByRole('dialog').filter({
      has: page.getByRole('heading', { name: '신고하기', exact: true }),
    });
    await menu.getByRole('button', { name: '댓글 신고' }).click();
    const form = page.getByRole('dialog').filter({
      has: page.getByRole('heading', { name: '댓글 신고', exact: true }),
    });
    await form.getByRole('radio', { name: '스팸·광고' }).check();
    await form.getByRole('textbox').fill('신고 대상이 바뀌면 안 되는 내용');
    await form.getByRole('button', { name: '신고 접수', exact: true }).click();
    await page.waitForFunction(
      (): boolean => document.documentElement.dataset.reportWaiting === 'true',
    );
    await expect(form.getByRole('button', { name: '취소' })).toBeDisabled();
    await page.keyboard.press('Escape');
    await expect(form).toBeVisible();
    await page.mouse.click(8, 8);
    await expect(form).toBeVisible();
    await expect(form.getByRole('textbox')).toHaveValue(
      '신고 대상이 바뀌면 안 되는 내용',
    );

    await page.evaluate((): void => {
      window.dispatchEvent(new Event('release-report'));
    });
    await expect(form.getByRole('status')).toHaveText(
      '신고가 접수되었어요. 운영자가 내용을 확인할게요.',
    );
    await form.getByRole('button', { name: '확인', exact: true }).click();
    await expect(form).not.toBeVisible();
    await comment.getByRole('button', { name: '댓글 더보기' }).click();
    await menu.getByRole('button', { name: '작성자 신고' }).click();
    const authorForm = page.getByRole('dialog').filter({
      has: page.getByRole('heading', { name: '작성자 신고', exact: true }),
    });
    await expect(authorForm.getByRole('textbox')).toBeEmpty();
    await authorForm.getByRole('radio', { name: '기타' }).check();
    const received = page.waitForResponse(
      (response): boolean =>
        response.request().method() === 'POST' &&
        new URL(response.url()).pathname === '/api/v1/reports',
    );
    await authorForm
      .getByRole('button', { name: '신고 접수', exact: true })
      .click();
    const response = await received;
    const body: unknown = response.request().postDataJSON();
    expect(body).toEqual({
      targetType: 'USER',
      targetId: '00000000-0000-4000-8000-000000000100',
      reason: 'OTHER',
    });
    await expect(authorForm.getByRole('status')).toHaveText(
      '신고가 접수되었어요. 운영자가 내용을 확인할게요.',
    );
  });

  test('본인 피드 삭제를 취소하면 유지하고 확인하면 삭제한다', async ({
    page,
  }): Promise<void> => {
    await page.goto(detailPath);
    const options = page
      .getByRole('article')
      .getByRole('button', { name: '옵션 더보기 아이콘' });
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

test.describe('피드 상세 좋아요', (): void => {
  test.use({ storageState: AUTHENTICATED_STORAGE_STATE });

  test('상세에서 좋아요를 누르거나 취소하고 목록으로 돌아오면 변경된 좋아요가 유지된다', async ({
    page,
  }): Promise<void> => {
    const listRequests: string[] = [];
    page.on('request', (request): void => {
      if (
        request.method() === 'GET' &&
        new URL(request.url()).pathname === '/api/v1/feeds'
      ) {
        listRequests.push(request.url());
      }
    });
    await page.goto('/');
    const articles = page.getByRole('article');
    await expect(articles).toHaveCount(20);
    const firstFeed = articles.first();
    const listLike = firstFeed.getByRole('button', { name: /^\d+$/ }).first();
    const initialCount = Number(await listLike.textContent());
    const requestCount = listRequests.length;

    for (const liked of [true, false]) {
      await firstFeed.getByRole('button', { name: /^\d+$/ }).nth(1).click();
      await expect(page).toHaveURL(/\/feeds\/[^/]+$/);
      const detailLike = page
        .getByRole('article')
        .getByRole('button', { name: /^\d+$/ })
        .first();
      await detailLike.click();
      const expectedCount = String(initialCount + (liked ? 1 : 0));
      await expect(detailLike).toHaveText(expectedCount);
      await expect(detailLike).toBeEnabled();
      const icon = await detailLike.locator('img').getAttribute('src');
      if (icon === null) throw new Error('좋아요 아이콘이 없습니다.');

      await page.getByRole('button', { name: '뒤로가기 아이콘' }).click();
      await expect(page).toHaveURL('/');
      await expect(articles).toHaveCount(20);
      await expect(listLike).toHaveText(expectedCount);
      await expect(listLike.locator('img')).toHaveAttribute('src', icon);
      expect(listRequests).toHaveLength(requestCount);
    }
  });
});

test.describe('피드 상세 삭제', (): void => {
  test.use({ storageState: AUTHENTICATED_STORAGE_STATE });

  test('댓글 삭제: 실패하면 열린 모달 안에 오류를 표시하고 재시도할 수 있다', async ({
    page,
  }): Promise<void> => {
    await page.setExtraHTTPHeaders({
      [MOCK_SCENARIO_HEADER]: MOCK_SCENARIOS.feedCommentDeleteFailure,
    });
    await page.goto(detailPath);
    await page
      .getByRole('listitem')
      .getByRole('button', { name: '옵션 더보기 아이콘' })
      .first()
      .click();
    await page
      .getByRole('dialog')
      .filter({
        has: page.getByRole('heading', {
          name: FEED_DETAIL_COPY.commentManageTitle,
          exact: true,
        }),
      })
      .getByRole('button', { name: /댓글 삭제$/ })
      .click();
    const modal = page.getByRole('dialog').filter({
      has: page.getByRole('heading', {
        name: FEED_DETAIL_COPY.commentDeleteConfirmTitle,
        exact: true,
      }),
    });
    const submit = modal.getByRole('button', {
      name: FEED_DETAIL_COPY.commentDeleteAction,
      exact: true,
    });
    await submit.click();

    await expect(modal).toBeVisible();
    await expect(modal.getByRole('alert')).toHaveText(
      ERROR_MESSAGES.FEED_COMMENT_DELETION_FAILED,
    );
    await expect(modal.getByRole('alert')).toBeVisible();
    await expect(
      page.getByText(ERROR_MESSAGES.FEED_COMMENT_DELETION_FAILED, {
        exact: true,
      }),
    ).toHaveCount(1);
    await expect(page).toHaveURL(detailPath);
    await expect(
      modal.getByRole('button', { name: '취소', exact: true }),
    ).toBeEnabled();
    await expect(submit).toBeEnabled();

    await page.setExtraHTTPHeaders({});
    await submit.click();
    await expect(modal).toBeHidden();
    await expect(
      page.getByText(ERROR_MESSAGES.FEED_COMMENT_DELETION_FAILED, {
        exact: true,
      }),
    ).toHaveCount(0);
  });

  test('상세에서 삭제하고 목록으로 돌아오면 삭제한 피드가 다시 표시되지 않는다', async ({
    page,
  }): Promise<void> => {
    const listRequests: string[] = [];
    page.on('request', (request): void => {
      if (
        request.method() === 'GET' &&
        new URL(request.url()).pathname === '/api/v1/feeds'
      ) {
        listRequests.push(request.url());
      }
    });
    await page.goto('/');
    const articles = page.getByRole('article');
    await expect(articles).toHaveCount(20);
    const options = page.getByRole('button', { name: '옵션 더보기 아이콘' });
    const ownFeedCount = await options.count();
    const ownFeed = articles.filter({ has: options }).first();
    const requestCount = listRequests.length;
    await ownFeed.getByRole('button', { name: /^\d+$/ }).nth(1).click();
    await expect(page).toHaveURL(/\/feeds\/[^/]+$/);
    await expect(articles).toHaveCount(1);

    await page
      .getByRole('article')
      .getByRole('button', { name: '옵션 더보기 아이콘' })
      .click();
    await page
      .getByRole('dialog')
      .filter({
        has: page.getByRole('heading', { name: '피드 관리', exact: true }),
      })
      .getByRole('button', { name: /피드 삭제$/ })
      .click();
    await page
      .getByRole('dialog')
      .filter({
        has: page.getByRole('heading', {
          name: '이 피드를 삭제할까요?',
          exact: true,
        }),
      })
      .getByRole('button', { name: /피드 삭제$/ })
      .click();

    await expect(page).toHaveURL('/');
    await expect(articles).toHaveCount(19);
    await expect(options).toHaveCount(ownFeedCount - 1);
    expect(listRequests).toHaveLength(requestCount);
  });
});
