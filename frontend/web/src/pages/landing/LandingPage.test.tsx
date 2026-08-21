import { describe, expect, it, jest } from '@jest/globals';
import { render, screen, within } from '@testing-library/react';
import LandingPage from './LandingPage';

jest.mock('../../assets/icons/kakao.svg', () => 'kakao.svg');
jest.mock('../../assets/images/harudle-logo.png', () => 'harudle-logo.png');
jest.mock('../../assets/images/login-hero.png', () => 'login-hero.png');
jest.mock('../../assets/images/writing-scene.png', () => 'writing-scene.png');
jest.mock(
  '../../assets/images/empty-person-and-dog.png',
  () => 'empty-person-and-dog.png',
);
jest.mock(
  './assets/guest-diary-cat-keyboard.png',
  () => 'guest-diary-cat-keyboard.png',
);
jest.mock('./assets/guest-diary-friend.jpg', () => 'guest-diary-friend.jpg');
jest.mock('./assets/guest-diary-workout.png', () => 'guest-diary-workout.png');

describe('로그인 유도 랜딩 페이지', () => {
  it('호출부가 히어로 액션과 마지막 콘텐츠를 명시적으로 구성할 수 있다', () => {
    render(
      <LandingPage
        heroAction={<button type="button">무료로 사용해보기</button>}
        finalAction={null}
        trialSection={<section aria-label="무료 네컷 체험" />}
      />,
    );

    expect(
      screen.getByRole('button', { name: '무료로 사용해보기' }),
    ).toBeInTheDocument();
    expect(
      screen.queryByRole('link', { name: '카카오로 시작하기' }),
    ).not.toBeInTheDocument();
    expect(
      screen.getByRole('region', { name: '무료 네컷 체험' }),
    ).toBeInTheDocument();
  });

  it('랜딩 내부 레이아웃 변화를 관찰하고 언마운트 시 해제한다', () => {
    const observe = jest.fn();
    const disconnect = jest.fn();
    const originalResizeObserver = globalThis.ResizeObserver;

    globalThis.ResizeObserver = jest.fn(() => ({
      observe,
      unobserve: jest.fn(),
      disconnect,
    })) as unknown as typeof ResizeObserver;

    const { unmount } = render(<LandingPage />);

    expect(observe).toHaveBeenCalledTimes(3);

    unmount();

    expect(disconnect).toHaveBeenCalledTimes(1);
    globalThis.ResizeObserver = originalResizeObserver;
  });

  it('일상을 그림으로 만드는 핵심 가치를 안내한다', () => {
    render(<LandingPage />);

    expect(
      screen.getByRole('heading', {
        name: /일상을 그림으로\s*만들어드려요/,
      }),
    ).toBeInTheDocument();
    expect(
      screen.getByText('찍지 못했던 일상을 그림으로 만들어드립니다'),
    ).toBeInTheDocument();
  });

  it('네컷 예시와 과정 위의 중간 연결 문구를 표시하지 않는다', () => {
    render(<LandingPage />);

    expect(screen.queryByText('하루를 적으면')).not.toBeInTheDocument();
    expect(screen.queryByText('네컷이 되는 과정')).not.toBeInTheDocument();
  });

  it('네컷 예시와 사용 방법을 하나의 연속된 흐름으로 묶는다', () => {
    render(<LandingPage />);

    const storyFlow = screen.getByRole('region', {
      name: '하루가 네컷이 되는 흐름',
    });

    expect(
      within(storyFlow).getByRole('region', {
        name: '완성된 네컷 그림 일기 예시',
      }),
    ).toBeInTheDocument();
    expect(
      within(storyFlow).getByRole('region', {
        name: '이렇게 하루를 남겨요',
      }),
    ).toBeInTheDocument();
  });

  it('하루들 손그림 캐릭터가 기록부터 시작까지의 흐름을 안내한다', () => {
    render(<LandingPage />);

    const hero = screen.getByRole('region', {
      name: /일상을 그림으로\s*만들어드려요/,
    });
    const process = screen.getByRole('region', {
      name: '이렇게 하루를 남겨요',
    });
    const finalCta = screen.getByRole('region', {
      name: /재밌는 이야기를 만들어\s*친구에게 공유해보세요/,
    });

    expect(
      within(hero).getByRole('img', {
        name: '사람들과 강아지가 함께 하루를 시작하는 모습',
      }),
    ).toHaveAttribute('src', 'login-hero.png');
    expect(
      within(process).getByRole('img', {
        name: '사람이 강아지와 함께 오늘의 이야기를 기록하는 모습',
      }),
    ).toHaveAttribute('src', 'writing-scene.png');
    expect(
      within(finalCta).getByRole('img', {
        name: '사람과 강아지가 함께 새로운 네컷을 시작하는 모습',
      }),
    ).toHaveAttribute('src', 'empty-person-and-dog.png');
  });

  it('첫 화면 마스코트 바로 아래에서 카카오 로그인을 시작할 수 있다', () => {
    render(<LandingPage />);

    const hero = screen.getByRole('region', {
      name: /일상을 그림으로\s*만들어드려요/,
    });
    const mascot = within(hero).getByRole('img', {
      name: '사람들과 강아지가 함께 하루를 시작하는 모습',
    });
    const loginLink = within(hero).getByRole('link', {
      name: '카카오로 시작하기',
    });

    expect(
      mascot.compareDocumentPosition(loginLink) &
        Node.DOCUMENT_POSITION_FOLLOWING,
    ).toBe(Node.DOCUMENT_POSITION_FOLLOWING);
    expect(loginLink).toHaveAttribute('href', '/oauth2/authorization/kakao');
  });

  it('카카오 로그인 아래에서 흰색 히어로가 끝나고 연보라 구간이 시작된다', () => {
    render(<LandingPage />);

    const hero = screen.getByRole('region', {
      name: /일상을 그림으로\s*만들어드려요/,
    });
    const loginLink = within(hero).getByRole('link', {
      name: '카카오로 시작하기',
    });
    const heroVisual = loginLink.parentElement;

    expect(hero).toHaveStyle({ paddingBottom: '32px' });
    expect(heroVisual).not.toBeNull();
    expect(heroVisual).toHaveStyle({ marginBottom: '0' });
  });

  it('별도 가로 조작 없이 세로 흐름에서 이용 과정을 모두 설명한다', () => {
    render(<LandingPage />);

    const process = screen.getByRole('region', {
      name: '이렇게 하루를 남겨요',
    });
    const processSteps = within(process).getAllByRole('listitem');

    expect(processSteps).toHaveLength(3);
    expect(
      within(process).getByText('있었던 일들을 적어요'),
    ).toBeInTheDocument();
    expect(
      within(process).getByText('그림 일기를 그려드릴게요!'),
    ).toBeInTheDocument();
    expect(
      within(process).getByText('친구에게 공유해서 함께 즐겨보세요'),
    ).toBeInTheDocument();
    expect(within(process).queryByRole('button')).not.toBeInTheDocument();
  });

  it('버튼 조작 없이 스크롤 흐름에서 세 개의 네컷 예시를 제공한다', () => {
    render(<LandingPage />);

    const diaryImages = [
      screen.getByRole('img', {
        name: '러닝머신을 타고 야식을 먹은 하루를 담은 네컷 그림 일기',
      }),
      screen.getByRole('img', {
        name: '마감 직전 키보드를 차지한 고양이의 모습을 담은 네컷 그림 일기',
        hidden: true,
      }),
      screen.getByRole('img', {
        name: '게임 속 친구와의 하루를 담은 네컷 그림 일기',
        hidden: true,
      }),
    ];

    diaryImages.forEach((image) => {
      expect(image).toHaveAttribute('decoding', 'async');
    });
    expect(diaryImages[0]).toHaveAttribute('loading', 'eager');
    expect(diaryImages[1]).toHaveAttribute('loading', 'lazy');
    expect(diaryImages[2]).toHaveAttribute('loading', 'lazy');
    expect(
      screen.queryByRole('button', { name: '네컷 그림으로 보기' }),
    ).not.toBeInTheDocument();
  });

  it('입력한 하루를 각 네컷보다 먼저 보여주고 불필요한 안내는 제거한다', () => {
    render(<LandingPage />);

    const showcase = screen.getByRole('region', {
      name: '완성된 네컷 그림 일기 예시',
    });
    const activeStory = within(showcase).getByRole('group', {
      name: '현재 네컷을 만든 이야기',
    });
    const diaryPairs = [
      {
        caption: '5분 운동하고 야식먹기',
        alt: '러닝머신을 타고 야식을 먹은 하루를 담은 네컷 그림 일기',
        src: 'guest-diary-workout.png',
      },
      {
        caption: '마감 직전 키보드를 차지한 고양이',
        alt: '마감 직전 키보드를 차지한 고양이의 모습을 담은 네컷 그림 일기',
        src: 'guest-diary-cat-keyboard.png',
      },
      {
        caption: '게임 친구와 투닥거리던 밤',
        alt: '게임 속 친구와의 하루를 담은 네컷 그림 일기',
        src: 'guest-diary-friend.jpg',
      },
    ];

    expect(
      within(activeStory).getByText('5분 운동하고 야식먹기'),
    ).toBeInTheDocument();
    expect(activeStory).not.toHaveAttribute('aria-live');
    expect(
      screen.queryByText('아래로 내려 세 개의 하루를 만나보세요'),
    ).not.toBeInTheDocument();
    expect(
      within(showcase).queryByText('네컷으로 남은 하루'),
    ).not.toBeInTheDocument();
    expect(showcase).not.toHaveTextContent(/\d{2}\s*\/\s*03/);

    diaryPairs.forEach(({ caption, alt, src }) => {
      const image = screen.getByRole('img', { name: alt, hidden: true });
      const figure = image.closest('figure');

      expect(figure).not.toBeNull();

      const pairedCaption = within(figure as HTMLElement).getByText(caption);

      expect(
        pairedCaption.compareDocumentPosition(image) &
          Node.DOCUMENT_POSITION_FOLLOWING,
      ).toBe(Node.DOCUMENT_POSITION_FOLLOWING);
      expect(image).toHaveAttribute('src', src);
    });
  });

  it('첫 화면과 마지막 CTA가 모두 기존 카카오 OAuth 경로로 연결된다', () => {
    render(<LandingPage />);

    const finalCta = screen.getByRole('region', {
      name: /재밌는 이야기를 만들어\s*친구에게 공유해보세요/,
    });
    const finalLoginLink = within(finalCta).getByRole('link', {
      name: '카카오로 시작하기',
    });

    const ctaLinks = screen.getAllByRole('link', {
      name: '카카오로 시작하기',
    });

    expect(ctaLinks).toHaveLength(2);
    expect(finalLoginLink).toHaveAttribute(
      'href',
      '/oauth2/authorization/kakao',
    );
  });
});
