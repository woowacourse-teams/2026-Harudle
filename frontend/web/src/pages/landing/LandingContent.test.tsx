import { afterEach, describe, expect, it, jest } from '@jest/globals';
import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import LandingContent from './LandingContent';

jest.mock('react-router', () => ({
  Link: ({
    to,
    children,
    ...props
  }: {
    to: string;
    children: import('react').ReactNode;
  }) => (
    <a href={to} {...props}>
      {children}
    </a>
  ),
}));

jest.mock('./LandingFeedPreview', () => () => <p>공개 피드 미리보기</p>);

jest.mock('./assets/chat-me.png', () => 'chat-me.png');
jest.mock('./assets/chat-friend.png', () => 'chat-friend.png');
jest.mock('./assets/slack-toothpaste.png', () => 'slack-toothpaste.png');
jest.mock('./assets/slack-favorite.png', () => 'slack-favorite.png');
jest.mock('./assets/slack-song-quiz.png', () => 'slack-song-quiz.png');
jest.mock('./assets/slack-chorok.png', () => 'slack-chorok.png');
jest.mock('./assets/slack-iq.png', () => 'slack-iq.png');
jest.mock('./assets/slack-ihyun.png', () => 'slack-ihyun.png');
jest.mock('./assets/friends-birthday.png', () => 'friends-birthday.png');
jest.mock(
  './assets/work-different-pages.png',
  () => 'work-different-pages.png',
);
jest.mock('./assets/school-presentation.png', () => 'school-presentation.png');
jest.mock('../../assets/images/harudle-logo.webp', () => 'harudle-logo.webp');
jest.mock(
  '../../assets/images/login-shared-comic.png',
  () => 'login-shared-comic.png',
);

const originalScrollTo = HTMLElement.prototype.scrollTo;
afterEach(() => {
  HTMLElement.prototype.scrollTo = originalScrollTo;
});

describe('브랜드 소개와 체험을 연결하는 랜딩', () => {
  it('랜딩에는 게스트 체험 입력과 버튼이 표시되지 않는다', () => {
    render(<LandingContent trialSection={null} />);
    expect(
      screen.queryByRole('button', { name: '직접 만들어 보기' }),
    ).not.toBeInTheDocument();
    expect(screen.queryByRole('textbox')).not.toBeInTheDocument();
    const final = screen.getByRole('region', { name: /최근에 같이 웃었던/ });
    expect(
      within(final).getByRole('link', { name: '하루들 시작하기' }),
    ).toHaveAttribute('href', '/');
  });

  it('상단 메뉴에서 기록 보호 영역으로 이동한다', async () => {
    HTMLElement.prototype.scrollTo = jest.fn();
    const user = userEvent.setup();
    render(<LandingContent trialSection={null} />);
    const menu = screen.getByRole('navigation', { name: '랜딩 메뉴' });
    await user.click(within(menu).getByRole('link', { name: '기록 보호' }));
    expect(HTMLElement.prototype.scrollTo).toHaveBeenCalled();
  });

  it('기록 보호 안내와 자주 묻는 질문을 보여준다', async () => {
    const user = userEvent.setup();
    render(<LandingContent trialSection={null} />);
    expect(
      screen.getByRole('heading', { name: /일기 원문은/ }),
    ).toBeInTheDocument();
    const question = screen.getByText('하루들에서는 어떻게 만화를 만드나요?');
    await user.click(question);
    expect(screen.getByText(/기억하고 싶은 순간을 글로 적으면/)).toBeVisible();
    expect(
      screen.getByRole('link', { name: /개인정보처리방침 자세히 보기/ }),
    ).toHaveAttribute('href', '/legal/privacy');
  });

  it('첫 화면에서 피드와 체험으로 이동할 수 있다', () => {
    render(
      <LandingContent trialSection={<textarea aria-label="체험 내용" />} />,
    );
    const hero = screen.getByRole('region', {
      name: /우리끼리 통하는\s*네컷만화/,
    });
    expect(
      within(hero).getByRole('button', { name: '직접 만들어 보기' }),
    ).toBeInTheDocument();
    expect(
      within(hero).getByRole('link', { name: '하루들 시작하기' }),
    ).toHaveAttribute('href', '/');
    expect(
      within(hero).queryByRole('link', { name: '로그인' }),
    ).not.toBeInTheDocument();
  });

  it('체험 버튼은 페이지 이동 없이 입력 영역으로 스크롤하고 입력창에 초점을 준다', async () => {
    const scrollTo = jest.fn();
    HTMLElement.prototype.scrollTo = scrollTo;
    const user = userEvent.setup();
    render(
      <LandingContent trialSection={<textarea aria-label="체험 내용" />} />,
    );
    await user.click(screen.getByRole('button', { name: '직접 만들어 보기' }));
    expect(scrollTo).toHaveBeenCalledWith({
      top: expect.any(Number),
      behavior: 'smooth',
    });
    expect(screen.getByRole('textbox', { name: '체험 내용' })).toHaveFocus();
  });

  it('예시 선택 시 내용과 실제 네컷만화를 함께 바꾼다', async () => {
    const user = userEvent.setup();
    render(<LandingContent trialSection={null} />);
    const section = screen.getByRole('region', { name: /사진은 없어도/ });
    await user.click(screen.getByRole('button', { name: '동료와' }));
    expect(
      within(section).getByText(/나는 첫 페이지를 동료는 마지막 페이지/),
    ).toBeInTheDocument();
    expect(
      within(section).getByRole('img', {
        name: '서로 다른 페이지 네컷만화',
      }),
    ).toBeInTheDocument();
    expect(
      within(section).queryByText(/실제 생성 결과가 아니에요/),
    ).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: '동료와' })).toHaveAttribute(
      'aria-pressed',
      'true',
    );
    expect(screen.getByRole('button', { name: '친구와' })).toHaveAttribute(
      'aria-pressed',
      'false',
    );
  });

  it('공유 예시에서 네컷 이미지와 지정한 프로필을 보여준다', () => {
    render(<LandingContent trialSection={null} />);
    expect(
      screen.getByRole('figure', { name: '공유 장면 예시' }),
    ).toBeInTheDocument();
    expect(
      screen.queryByText(/혼자 간직하고 싶은 순간은 공유하지 않고/),
    ).not.toBeInTheDocument();
    expect(
      screen.queryByRole('region', { name: '적고, 만들고, 나눠요' }),
    ).not.toBeInTheDocument();
    expect(
      screen.queryByRole('list', { name: '저장과 공유 기능' }),
    ).not.toBeInTheDocument();
    const chat = screen.getByRole('figure', { name: '공유 장면 예시' });
    expect(
      within(chat).getByRole('img', {
        name: '실패한 생일 서프라이즈 네컷만화',
      }),
    ).toHaveAttribute('src', 'friends-birthday.png');
    expect(
      within(chat).getAllByRole('img', { name: '나의 프로필' })[0],
    ).toHaveAttribute('src', 'chat-me.png');
    expect(
      within(chat).getByRole('img', { name: '친구의 프로필' }),
    ).toHaveAttribute('src', 'chat-friend.png');
  });

  it('기존 공유 사례 아래에 인기 피드를 두고 이용 현황을 미리보기 아래로 옮긴다', () => {
    render(<LandingContent trialSection={null} />);
    expect(
      screen.getByRole('figure', { name: '공유 장면 예시' }),
    ).toBeInTheDocument();
    expect(
      screen.getByRole('figure', { name: '우아한테크코스의 슬랙 공유 사례' }),
    ).toBeInTheDocument();
    const feed = screen.getByRole('region', {
      name: /피드에서/,
    });
    expect(within(feed).getByText('공개 피드 미리보기')).toBeInTheDocument();
    expect(within(feed).getAllByRole('definition')).toHaveLength(2);
    const share = screen.getByRole('region', { name: /생성한 만화를/ });
    expect(within(share).queryByRole('definition')).not.toBeInTheDocument();
    expect(
      within(feed).getByRole('link', { name: '피드 둘러보기' }),
    ).toHaveAttribute('href', '/');
  });

  it('사용한 체험의 CTA는 로그인 안내로 바꾸고 해당 링크로 초점을 준다', async () => {
    HTMLElement.prototype.scrollTo = jest.fn();
    const user = userEvent.setup();
    render(
      <LandingContent
        trialActionLabel="로그인하고 계속 만들기"
        trialSection={<a href="/login">계속 만들기</a>}
      />,
    );
    await user.click(
      screen.getByRole('button', { name: '로그인하고 계속 만들기' }),
    );
    expect(screen.getByRole('link', { name: '계속 만들기' })).toHaveFocus();
  });
});
