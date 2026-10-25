import { expect, test } from '@playwright/test';

interface RequestedNavigation {
  readonly url: string;
}

test.use({ browserName: 'chromium', headless: true });

test('문의 버튼을 누르면 운영팀 주소와 문의 양식을 메일 작성으로 전달한다', async ({
  page,
  context,
}): Promise<void> => {
  await page.goto('/contact');
  const session = await context.newCDPSession(page);
  let requestedMailto = '';

  // 메일 앱을 조작하거나 발송하지 않고 브라우저가 요청한 mailto 주소만 확인한다.
  session.on(
    'Page.frameRequestedNavigation',
    ({ url }: RequestedNavigation): void => {
      if (url.startsWith('mailto:')) requestedMailto = url;
    },
  );

  try {
    await session.send('Page.enable');
    await page.getByRole('button', { name: '메일로 문의하기' }).click();
    await expect.poll((): string => requestedMailto).toMatch(/^mailto:/);

    const mailto = new URL(requestedMailto);
    expect(mailto.protocol).toBe('mailto:');
    expect(mailto.pathname).toBe('harudle.official@gmail.com');
    expect([...mailto.searchParams.keys()]).toEqual(['subject', 'body']);
    expect(mailto.searchParams.get('subject')).toBe('[하루들 문의]');
    expect(mailto.searchParams.get('body')).toBe(
      '비밀번호나 일기 원문은 적지 말아 주세요.\n\n문의 종류: 이용 오류 / 계정·로그인 / 기타\n\n문의 내용:\n\n문제 발생 시점 (선택):',
    );
    await expect(page).toHaveURL('/contact');
    await expect(page.getByText(/접수.*완료/)).toHaveCount(0);
  } finally {
    await session.detach();
  }
});
