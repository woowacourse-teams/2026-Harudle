import { expect, test } from '@playwright/test';

test.describe('통합 랜딩의 1회 체험', () => {
  test('소개에서 바로 생성하고 재방문 시 추가 생성을 막는다', async ({
    page,
  }) => {
    await page.setViewportSize({ width: 390, height: 844 });
    await page.goto('/landing');
    await expect(page.getByRole('heading', { level: 1 })).toHaveText(
      '우리끼리 통하는네컷만화',
    );
    const textbox = page.getByRole('textbox', { name: '네컷만화로 만들 내용' });
    await expect(textbox).toBeAttached();
    await expect
      .poll(() =>
        page.evaluate(
          () =>
            document.documentElement.scrollHeight -
            document.documentElement.clientHeight,
        ),
      )
      .toBe(0);
    await page.getByRole('button', { name: '네컷만화 만들어 보기' }).click();
    await expect(textbox).toBeFocused();
    await expect
      .poll(() => page.locator('main').evaluate((element) => element.scrollTop))
      .toBeGreaterThan(0);
    await expect.poll(() => page.evaluate(() => window.scrollY)).toBe(0);
    await expect(page.locator('#root')).toHaveJSProperty('scrollTop', 0);
    await expect(textbox).toBeInViewport();
    await textbox.fill(
      '친구와 회의하다 서로 다른 페이지를 보고 있었다는 걸 알고 한참 웃었다.',
    );
    await page
      .getByRole('button', { name: '네컷만화 만들기', exact: true })
      .click();
    await expect(
      page.getByRole('status', { name: '네컷만화를 만들고 있어요' }),
    ).toBeVisible();
    await expect(
      page
        .locator('article[aria-labelledby="landing-trial-result-title"]')
        .getByRole('img'),
    ).toBeVisible();
    await expect(page).toHaveURL('/landing');
    await expect(
      page.getByRole('link', { name: '카카오로 시작하고 계속 만들기' }),
    ).toHaveAttribute('href', '/oauth2/authorization/kakao');
    await page.reload();
    await expect(
      page.getByRole('heading', { name: '게스트 체험을 이미 사용했어요' }),
    ).toBeAttached();
    await expect(textbox).toHaveCount(0);
    await expect(
      page.getByRole('button', { name: '로그인하고 계속 만들기' }),
    ).toBeVisible();
  });

  test('게스트 결과 조회는 landing 하위에서 유지한다', async ({ page }) => {
    await page.goto('/landing');
    await page
      .getByRole('textbox', { name: '네컷만화로 만들 내용' })
      .fill('친구와 함께 공원에서 산책하며 오래 웃었던 순간이었다.');
    await page
      .getByRole('button', { name: '네컷만화 만들기', exact: true })
      .click();
    await expect(
      page
        .locator('article[aria-labelledby="landing-trial-result-title"]')
        .getByRole('img'),
    ).toBeAttached();
    await page.goto('/landing/result/00000000-0000-4000-8000-000000000001');
    await expect(page.getByRole('heading', { level: 1 })).toBeVisible();
    await expect(page.getByRole('img', { name: /네컷만화$/ })).toBeVisible();
    await expect(page.getByRole('heading', { name: '내용' })).toBeVisible();
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

  test('하단 버튼은 첫 화면 이후 보이고 체험 섹션에서 숨는다', async ({
    page,
  }) => {
    await page.setViewportSize({ width: 390, height: 844 });
    await page.goto('/landing');
    const sticky = page.getByRole('complementary', { name: '빠른 체험' });
    await expect(sticky).toHaveCount(0);
    await page.getByRole('button', { name: '아래 예시 보기' }).click();
    await expect.poll(() => page.evaluate(() => window.scrollY)).toBe(0);
    await expect(sticky).toBeVisible();
    await sticky.getByRole('button').click();
    const input = page.getByRole('textbox', { name: '네컷만화로 만들 내용' });
    await expect(input).toBeFocused();
    await expect(input).toBeVisible();
    await expect(sticky).toHaveCount(0);
    await expect.poll(() => page.evaluate(() => window.scrollY)).toBe(0);
    await expect(input).toHaveAttribute('rows', '4');
    await expect(
      page.getByRole('heading', { name: '어떤 순간을 네컷만화로 만들까요?' }),
    ).toHaveCount(0);
    await page
      .getByRole('heading', { name: /사진은 없어도/ })
      .scrollIntoViewIfNeeded();
    await expect(sticky).toBeVisible();
  });
});
