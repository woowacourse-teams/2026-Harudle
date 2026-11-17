import { expect, test, type Page } from '@playwright/test';
import { GUEST_TRIAL_COPY } from '../src/pages/feed/guest-trial/copy';
import {
  MOCK_SCENARIO_HEADER,
  MOCK_SCENARIOS,
} from '../src/mocks/mockScenarios';

const generateGuestDiary = async (page: Page): Promise<string> => {
  await page.clock.install();
  await page.goto('/');
  await page
    .getByRole('button', { name: GUEST_TRIAL_COPY.guideSpotlightAction })
    .click();
  const input = page.getByRole('dialog').filter({
    has: page.getByRole('heading', { name: GUEST_TRIAL_COPY.inputSheetTitle }),
  });
  await input
    .getByRole('textbox', { name: GUEST_TRIAL_COPY.inputLabel })
    .fill(
      '친구와 회의하다 서로 다른 페이지를 보고 있었다는 걸 알고 한참 웃었다.',
    );
  const acceptedResponse = page.waitForResponse(
    (response): boolean =>
      response.request().method() === 'POST' &&
      new URL(response.url()).pathname === '/api/v1/guest/diaries',
  );
  await input
    .getByRole('button', { name: GUEST_TRIAL_COPY.submitAction })
    .click();
  expect((await acceptedResponse).status()).toBe(202);
  await expect(input).toBeHidden();
  await expect(page.getByText(GUEST_TRIAL_COPY.generatingTitle)).toBeVisible();
  await page.clock.fastForward(10_000);
  const resultResponse = page.waitForResponse(
    (response): boolean =>
      response.request().method() === 'GET' &&
      new URL(response.url()).pathname.startsWith('/api/v1/guest/diaries/'),
  );
  await page
    .getByRole('button', { name: GUEST_TRIAL_COPY.readyAction })
    .click();
  const response = await resultResponse;
  expect(response.ok()).toBe(true);
  const diaryId = new URL(response.url()).pathname.split('/').at(-1);
  if (!diaryId) throw new Error('체험 만화 조회 주소에 일기 ID가 없습니다.');
  return diaryId;
};

test.describe('랜딩 진입과 게스트 체험', () => {
  test('랜딩에 직접 접속한 뒤 로그인 없이 공개 피드로 이동한다', async ({
    page,
  }): Promise<void> => {
    await page.setExtraHTTPHeaders({
      [MOCK_SCENARIO_HEADER]: MOCK_SCENARIOS.authRefreshFailure,
    });
    await page.setViewportSize({ width: 390, height: 844 });
    await page.goto('/landing');
    await expect(page).toHaveURL('/landing');
    const hero = page.getByRole('region', {
      name: /우리끼리 통하는\s*네컷만화/,
    });
    const feedLink = hero.getByRole('link', { name: '하루들 시작하기' });
    await expect(feedLink).toBeInViewport({ ratio: 1 });
    await feedLink.click();
    await expect(page).toHaveURL('/');
    await expect(page.getByRole('article')).toHaveCount(20);
    expect(
      await page.evaluate((): string | null =>
        localStorage.getItem('harudle.has-completed-oauth'),
      ),
    ).toBeNull();
    await page.goBack();
    await expect(page).toHaveURL('/landing');
    await expect(feedLink).toBeInViewport();
  });

  test('피드에서 한 번 생성하고 새로고침 후 같은 결과를 다시 연다', async ({
    page,
  }) => {
    const submissions: string[] = [];
    page.on('request', (request): void => {
      if (
        request.method() === 'POST' &&
        new URL(request.url()).pathname === '/api/v1/guest/diaries'
      ) {
        submissions.push(request.url());
      }
    });
    const diaryId = await generateGuestDiary(page);
    const result = page.getByRole('dialog').filter({
      has: page.getByRole('heading', {
        name: GUEST_TRIAL_COPY.resultSheetTitle,
      }),
    });
    const comic = result.getByRole('img', {
      name: GUEST_TRIAL_COPY.maskedImageLabel,
    });
    await expect(comic).toHaveAttribute('aria-busy', 'false');
    await expect(page).toHaveURL('/');
    await page.reload();
    const reopenedResult = page.waitForResponse(
      (response): boolean =>
        response.request().method() === 'GET' &&
        new URL(response.url()).pathname === `/api/v1/guest/diaries/${diaryId}`,
    );
    await page
      .getByRole('button', { name: '네컷만화 만들기', exact: true })
      .click();
    expect((await reopenedResult).ok()).toBe(true);
    await expect(comic).toHaveAttribute('aria-busy', 'false');
    expect(submissions).toHaveLength(1);

    await result
      .getByRole('button', { name: GUEST_TRIAL_COPY.loginAction })
      .click();
    await expect(page).toHaveURL('/login');
  });

  test('기존 랜딩 결과 링크에서 생성된 만화를 조회할 수 있다', async ({
    page,
  }) => {
    const diaryId = await generateGuestDiary(page);
    await page.goto(`/landing/result/${diaryId}`);
    await expect(page.getByRole('heading', { level: 1 })).toBeVisible();
    await expect(page.getByRole('img', { name: /네컷만화$/ })).toBeVisible();
    await expect(page.getByRole('heading', { name: '내용' })).toBeVisible();
    await page.getByRole('link', { name: '로그인하기' }).click();
    await expect(page).toHaveURL('/login');
    for (const label of [
      '카카오로 계속하기',
      'Google로 계속하기',
      'Apple로 계속하기',
    ]) {
      await expect(page.getByRole('button', { name: label })).toBeVisible();
    }
  });

  test('landing-try는 더 이상 별도 체험 페이지를 제공하지 않는다', async ({
    page,
  }) => {
    await page.goto('/landing-try');
    await expect(
      page.getByRole('heading', { name: '페이지를 찾을 수 없어요' }),
    ).toBeVisible();
    await expect(page.getByRole('textbox')).toHaveCount(0);
  });
});

test.describe('랜딩 스크롤과 채팅 연출', () => {
  test('실제 슬랙 반응·답글이 한 번 등장하고 작은 공유 글 뒤에 이용 현황으로 이어진다', async ({
    page,
  }, testInfo) => {
    await page.setViewportSize({ width: 320, height: 720 });
    await page.goto('/landing');
    const slack = page.getByRole('figure', {
      name: '우아한테크코스의 슬랙 공유 사례',
    });
    const main = page.locator('main');
    const start = await slack.evaluate((element) => {
      const scroller = element.closest('main');
      if (!scroller) throw new Error('랜딩 스크롤 영역 없음');
      return (
        scroller.scrollTop +
        element.getBoundingClientRect().top -
        scroller.getBoundingClientRect().top -
        20
      );
    });
    await main.evaluate(
      (element, top) => element.scrollTo({ top, behavior: 'instant' }),
      start,
    );
    await expect(slack).toHaveAttribute('data-slack-active', 'true');
    await expect(
      slack.getByText('그림일기-공유', { exact: false }),
    ).toBeVisible();
    await expect(slack.getByText('이산', { exact: true })).toBeVisible();
    await expect(
      slack.getByRole('img', { name: '직업병과 치약 네컷만화', exact: true }),
    ).toBeVisible();
    await expect
      .poll(() =>
        slack
          .locator('[data-slack-main] img')
          .first()
          .evaluate((image: HTMLImageElement) => image.naturalWidth),
      )
      .toBe(1024);
    await expect(slack.locator('[data-slack-main] img')).toHaveCount(1);
    const laugh = slack.getByRole('listitem', { name: '웃음 반응 15개' });
    await expect(laugh).toHaveText('ㅋㅋ15');
    await expect(
      slack
        .getByRole('list', { name: '실제 슬랙 반응' })
        .getByRole('listitem')
        .first(),
    ).toHaveAttribute('aria-label', '웃음 반응 15개');
    await expect(
      slack.getByRole('listitem', { name: '와우 반응 6개' }),
    ).toHaveText('와우6');
    await expect(
      slack.getByRole('listitem', { name: '경광등 반응 2개' }),
    ).toHaveText('🚨2');
    await expect(
      slack.getByRole('listitem', { name: '바보 반응 3개' }),
    ).toHaveText('바보3');
    await slack
      .getByText('localhost:2080', { exact: true })
      .scrollIntoViewIfNeeded();
    await expect(
      slack.getByText('댓글 6개 더 보기', { exact: false }),
    ).toBeVisible();
    await expect(slack.locator('[data-slack-reply]')).toHaveCSS('opacity', '1');
    const related = slack.getByRole('list', { name: '다른 네컷만화 공유 글' });
    await related.scrollIntoViewIfNeeded();
    await expect(related.locator(':scope > li').last()).toHaveCSS(
      'opacity',
      '1',
    );
    await expect(
      related.getByRole('img', { name: /네컷만화 썸네일/ }),
    ).toHaveCount(2);
    await expect(
      related.getByRole('img', { name: '아이큐의 프로필' }),
    ).toBeVisible();
    await expect(related.getByText('아이큐', { exact: true })).toBeVisible();
    await expect(
      slack.getByRole('article', { name: '초록의 댓글' }),
    ).toHaveCount(1);
    await expect(slack.getByRole('blockquote')).toHaveCount(0);
    await expect(slack.getByText(/\(.*\)/)).toHaveCount(0);
    await expect(slack.getByText(/가명/)).toHaveCount(0);
    await expect
      .poll(() =>
        slack.evaluate((element) => element.scrollWidth - element.clientWidth),
      )
      .toBe(0);
    // 다시 들어와도 반응 수를 0으로 돌리거나 메시지를 숨기지 않는다.
    await main.evaluate(
      (element, top) => element.scrollTo({ top, behavior: 'instant' }),
      start,
    );
    await expect(laugh).toHaveText('ㅋㅋ15');
    await expect(
      slack.locator('[data-slack-main] [data-comic-mask]').last(),
    ).toHaveCSS('opacity', '0');
    await page.setViewportSize({ width: 390, height: 1300 });
    await slack.screenshot({
      path: testInfo.outputPath('landing-slack-community.png'),
    });
    await page.getByRole('definition').first().scrollIntoViewIfNeeded();
    await expect(page.getByRole('definition').first()).toBeInViewport();
    await expect
      .poll(() =>
        page.evaluate(
          () =>
            document.documentElement.scrollHeight -
            document.documentElement.clientHeight,
        ),
      )
      .toBe(0);
    await expect.poll(() => page.evaluate(() => window.scrollY)).toBe(0);
  });

  test('스크롤로 채팅 말풍선이 등장하고 작은 화면에서도 내용이 잘리지 않는다', async ({
    page,
  }, testInfo) => {
    await page.setViewportSize({ width: 320, height: 720 });
    await page.goto('/landing');
    const message = page.getByText('어제 서프라이즈 사진 찍은 거 있어?', {
      exact: true,
    });
    const chat = page.getByRole('figure', { name: '공유 장면 예시' });
    await expect(chat).not.toHaveAttribute('data-revealed', 'true');
    await chat.scrollIntoViewIfNeeded();
    await expect(chat).toHaveAttribute('data-revealed', 'true');
    await expect(message).toBeVisible();
    await expect(chat.getByText('생일 작전방 · 3')).toBeVisible();
    await expect(
      chat.getByRole('img', {
        name: '실패한 생일 서프라이즈 네컷만화',
      }),
    ).toBeVisible();
    const overflow = await chat.evaluate(
      (element) => element.scrollWidth > element.clientWidth,
    );
    expect(overflow).toBe(false);
    const reaction = chat.getByText('ㅋㅋ 기사님도 같이 놀랐잖아');
    await reaction.scrollIntoViewIfNeeded();
    await expect(reaction.locator('..')).toHaveCSS('opacity', '1');
    await expect(reaction.locator('..')).toHaveCSS(
      'transform',
      'matrix(1, 0, 0, 1, 0, 0)',
    );
    await page.setViewportSize({ width: 390, height: 1100 });
    await chat.screenshot({ path: testInfo.outputPath('landing-chat.png') });
  });

  test('모션 감소 설정에서는 스크롤 전부터 내용을 숨기지 않는다', async ({
    page,
  }) => {
    await page.emulateMedia({ reducedMotion: 'reduce' });
    await page.goto('/landing');
    const message = page.getByText('어제 서프라이즈 사진 찍은 거 있어?', {
      exact: true,
    });
    await expect(message.locator('..').locator('..')).toHaveCSS('opacity', '1');
    await expect(message.locator('..').locator('..')).toHaveCSS(
      'animation-name',
      'none',
    );
    const slack = page.getByRole('figure', {
      name: '우아한테크코스의 슬랙 공유 사례',
    });
    await expect(
      slack.getByRole('listitem', { name: '웃음 반응 15개' }),
    ).toHaveText('ㅋㅋ15');
    await expect(slack.locator('[data-slack-reply]')).toHaveCSS('opacity', '1');
    await expect(slack.locator('[data-slack-typing]')).toHaveCSS(
      'display',
      'none',
    );
    const demo = page.locator('#landing-example');
    await expect(demo).toHaveAttribute('data-demo-stage', 'complete');
    await expect(demo.locator('[data-comic-mask]').first()).toHaveCSS(
      'opacity',
      '0',
    );
    await page.emulateMedia({ reducedMotion: 'no-preference' });
    await expect(slack).toHaveAttribute('data-slack-motion', 'disabled');
    await expect(slack.locator('[data-comic-mask]').first()).toHaveCSS(
      'animation-name',
      'none',
    );
  });

  test('예시는 처음 보일 때와 탭 변경 시 입력 뒤 네 컷을 보여준다', async ({
    page,
  }) => {
    await page.setViewportSize({ width: 390, height: 844 });
    await page.goto('/landing');
    let demo = page.locator('#landing-example');
    await expect(demo).toHaveAttribute('data-demo-stage', 'typing');
    await demo.scrollIntoViewIfNeeded();
    await expect(demo).toHaveAttribute('data-demo-stage', 'complete');
    await expect(demo.locator('[data-comic-mask]').last()).toHaveCSS(
      'opacity',
      '0',
    );
    await page.getByRole('button', { name: '동료와' }).click();
    demo = page.locator('#landing-example');
    await expect(demo).toHaveAttribute('data-demo-stage', 'typing');
    await expect(demo).toHaveAttribute('data-demo-stage', 'complete');
    await expect(
      demo.getByRole('img', { name: '서로 다른 페이지 네컷만화' }),
    ).toBeVisible();
  });

  test('하단 버튼은 첫 화면을 지나면 나타나고 마지막 시작하기 영역에서 숨는다', async ({
    page,
  }) => {
    await page.setViewportSize({ width: 390, height: 844 });
    await page.goto('/landing');
    const sticky = page.getByRole('complementary', {
      name: '빠른 서비스 이동',
    });
    await expect(sticky).toHaveCount(0);

    await page
      .getByRole('heading', { name: '생성한 만화를 공유해보세요' })
      .scrollIntoViewIfNeeded();
    await expect(sticky).toBeVisible();
    await expect(
      sticky.getByRole('link', { name: '하루들 시작하기' }),
    ).toHaveAttribute('href', '/');
    await expect.poll(() => page.evaluate(() => window.scrollY)).toBe(0);

    const final = page.getByRole('region', { name: /최근에 같이 웃었던/ });
    await final
      .getByRole('link', { name: '하루들 시작하기' })
      .scrollIntoViewIfNeeded();
    await expect(final).toBeInViewport();
    await expect(sticky).toHaveCount(0);
  });
});

test.describe('랜딩의 공개 피드 미리보기', () => {
  test('작은 화면에서 인기 피드 두 개를 미리 보고 상세로 이동한다', async ({
    page,
  }, testInfo) => {
    await page.setExtraHTTPHeaders({
      [MOCK_SCENARIO_HEADER]: MOCK_SCENARIOS.authRefreshFailure,
    });
    await page.setViewportSize({ width: 320, height: 720 });
    await page.goto('/landing');
    const feed = page.getByRole('region', { name: /피드에서/ });
    const list = feed.getByRole('list', { name: '공개 피드 미리보기' });
    await expect(list.getByRole('listitem')).toHaveCount(2);
    await expect(list.getByRole('link').first()).toHaveAttribute(
      'href',
      '/feeds/00000000-0000-4000-8000-000000000005',
    );
    await expect(list.getByRole('link').nth(1)).toHaveAttribute(
      'href',
      '/feeds/00000000-0000-4000-8000-000000000010',
    );
    await expect(
      list.getByRole('img', { name: '좋아요', exact: true }),
    ).toHaveCount(2);
    await expect(feed.getByRole('definition')).toHaveCount(2);

    await list.scrollIntoViewIfNeeded();
    await expect(list.getByRole('img').first()).toBeVisible();
    await expect
      .poll(() =>
        list
          .getByRole('img')
          .first()
          .evaluate((image: HTMLImageElement) => image.naturalWidth),
      )
      .toBeGreaterThan(0);
    await expect
      .poll(() =>
        feed.evaluate((element) => element.scrollWidth - element.clientWidth),
      )
      .toBe(0);
    await expect.poll(() => page.evaluate(() => window.scrollY)).toBe(0);
    await expect(
      page.getByRole('figure', { name: /슬랙|공유 장면/ }),
    ).toHaveCount(2);
    await feed
      .getByRole('link', { name: '피드 둘러보기' })
      .scrollIntoViewIfNeeded();
    await feed.getByRole('definition').first().scrollIntoViewIfNeeded();
    await page.setViewportSize({ width: 390, height: 844 });
    await feed.screenshot({
      path: testInfo.outputPath('landing-feed-preview.png'),
    });
    const first = list.getByRole('link').first();
    const href = await first.getAttribute('href');
    if (!href) throw new Error('피드 상세 링크 없음');
    expect(href).toMatch(/^\/feeds\//);
    await first.click();
    await expect(page).toHaveURL(href);
    await expect(
      page.getByRole('button', { name: '뒤로가기 아이콘' }),
    ).toBeVisible();
    await expect(
      page.getByRole('article').getByRole('img', { name: /네컷만화$/ }),
    ).toBeVisible();
  });

  test('미리보기 조회 실패 후 다시 불러오며 피드 링크를 유지한다', async ({
    page,
  }) => {
    await page.setExtraHTTPHeaders({
      [MOCK_SCENARIO_HEADER]: MOCK_SCENARIOS.feedListFailure,
    });
    await page.goto('/landing');
    const feed = page.getByRole('region', { name: /피드에서/ });
    await expect(feed.getByRole('status')).toHaveText(
      '네컷만화 미리보기를 불러오지 못했어요.',
    );
    await expect(
      feed.getByRole('link', { name: '피드 둘러보기' }),
    ).toHaveAttribute('href', '/');
    await page.setExtraHTTPHeaders({});
    await feed.getByRole('button', { name: '다시 불러오기' }).click();
    await expect(feed.getByRole('listitem')).toHaveCount(2);
  });

  test('공개 피드가 비어 있어도 피드로 이동할 수 있다', async ({ page }) => {
    await page.setExtraHTTPHeaders({
      [MOCK_SCENARIO_HEADER]: MOCK_SCENARIOS.feedListEmpty,
    });
    await page.goto('/landing');
    const feed = page.getByRole('region', { name: /피드에서/ });
    await expect(feed.getByRole('status')).toHaveText(
      '아직 공개 피드에 올라온 네컷만화가 없어요.',
    );
    await feed.getByRole('link', { name: '피드 둘러보기' }).click();
    await expect(page).toHaveURL('/');
  });
});
