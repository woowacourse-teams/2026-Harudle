import { useEffect, useRef, useState, type ReactNode } from 'react';
import { css } from '@emotion/react';
import emptyPersonAndDog from '../../assets/images/empty-person-and-dog.png';
import harudleLogo from '../../assets/images/harudle-logo.png';
import loginHero from '../../assets/images/login-hero.png';
import writingScene from '../../assets/images/writing-scene.png';
import { theme } from '../../styles/theme';
import GuestLoginCta from '../guest-trial/GuestLoginCta';
import catKeyboardDiary from './assets/guest-diary-cat-keyboard.png';
import friendDiary from './assets/guest-diary-friend.jpg';
import workoutDiary from './assets/guest-diary-workout.png';

const diaryExamples = [
  {
    image: workoutDiary,
    alt: '러닝머신을 타고 야식을 먹은 하루를 담은 네컷 그림 일기',
    caption: '5분 운동하고 야식먹기',
  },
  {
    image: catKeyboardDiary,
    alt: '마감 직전 키보드를 차지한 고양이의 모습을 담은 네컷 그림 일기',
    caption: '마감 직전 키보드를 차지한 고양이',
  },
  {
    image: friendDiary,
    alt: '게임 속 친구와의 하루를 담은 네컷 그림 일기',
    caption: '게임 친구와 투닥거리던 밤',
  },
];

const processSteps = [
  {
    title: '있었던 일들을 적어요',
    description: '사진이 없어도 괜찮아요\n편하게 적어주세요',
  },
  {
    title: '그림 일기를 그려드릴게요!',
    description: '귀여운 그림일기로 만나보세요',
  },
  {
    title: '친구에게 공유해서 함께 즐겨보세요',
    description: '놓쳤던 일상을 친구에게 공유하고\n재밌게 즐겨보세요',
  },
];

interface LandingPageProps {
  heroAction?: ReactNode;
  finalAction?: ReactNode;
  trialSection?: ReactNode;
}

const LandingPage = ({
  heroAction = <GuestLoginCta label="카카오로 시작하기" />,
  finalAction = <GuestLoginCta label="카카오로 시작하기" />,
  trialSection,
}: LandingPageProps) => {
  const pageRef = useRef<HTMLElement>(null);
  const showcaseRef = useRef<HTMLElement>(null);
  const processRef = useRef<HTMLElement>(null);
  const [activeDiaryIndex, setActiveDiaryIndex] = useState(0);
  const [visibleProcessStepCount, setVisibleProcessStepCount] = useState(0);

  useEffect(() => {
    const scrollContainer = pageRef.current;
    const showcase = showcaseRef.current;
    const processSection = processRef.current;

    if (!scrollContainer || !showcase || !processSection) {
      return;
    }

    let animationFrameId = 0;

    const updateScrollStory = () => {
      animationFrameId = 0;

      const viewportHeight = Math.max(
        scrollContainer.clientHeight,
        window.innerHeight,
        1,
      );
      const showcaseRect = showcase.getBoundingClientRect();
      const showcaseDistance = Math.max(
        showcase.offsetHeight - viewportHeight,
        1,
      );
      const showcaseProgress = Math.min(
        1,
        Math.max(0, -showcaseRect.top / showcaseDistance),
      );
      const nextDiaryIndex = Math.min(
        diaryExamples.length - 1,
        Math.round(showcaseProgress * (diaryExamples.length - 1)),
      );

      setActiveDiaryIndex((currentIndex) =>
        currentIndex === nextDiaryIndex ? currentIndex : nextDiaryIndex,
      );

      const processRect = processSection.getBoundingClientRect();
      const processRevealProgress = Math.min(
        1,
        Math.max(
          0,
          (viewportHeight * 0.84 - processRect.top) / (viewportHeight * 0.5),
        ),
      );
      const nextVisibleStepCount = Math.min(
        processSteps.length,
        Math.ceil(processRevealProgress * processSteps.length),
      );

      setVisibleProcessStepCount((currentCount) =>
        currentCount === nextVisibleStepCount
          ? currentCount
          : nextVisibleStepCount,
      );
    };

    const requestScrollUpdate = () => {
      if (animationFrameId === 0) {
        animationFrameId = window.requestAnimationFrame(updateScrollStory);
      }
    };

    updateScrollStory();
    scrollContainer.addEventListener('scroll', requestScrollUpdate, {
      passive: true,
    });
    window.addEventListener('resize', requestScrollUpdate);
    const resizeObserver =
      typeof ResizeObserver === 'undefined'
        ? null
        : new ResizeObserver(requestScrollUpdate);

    resizeObserver?.observe(scrollContainer);
    resizeObserver?.observe(showcase);
    resizeObserver?.observe(processSection);

    return () => {
      scrollContainer.removeEventListener('scroll', requestScrollUpdate);
      window.removeEventListener('resize', requestScrollUpdate);
      resizeObserver?.disconnect();

      if (animationFrameId !== 0) {
        window.cancelAnimationFrame(animationFrameId);
      }
    };
  }, []);

  return (
    <main ref={pageRef} css={pageStyle}>
      <header css={topBarStyle}>
        <img src={harudleLogo} alt="하루들" css={logoStyle} />
      </header>

      <section css={heroSectionStyle} aria-labelledby="landing-hero-title">
        <div css={heroCopyStyle}>
          <h1 id="landing-hero-title" css={heroTitleStyle}>
            일상을 <span css={accentStyle}>그림으로</span>
            <br />
            만들어드려요
          </h1>
          <p css={heroDescriptionStyle}>
            찍지 못했던 일상을 그림으로 만들어드립니다
          </p>
        </div>

        <div css={heroVisualStyle}>
          <img
            src={loginHero}
            alt="사람들과 강아지가 함께 하루를 시작하는 모습"
            loading="eager"
            decoding="async"
            css={heroIllustrationStyle}
          />
          {heroAction}
        </div>
      </section>

      <section css={storyFlowStyle} aria-label="하루가 네컷이 되는 흐름">
        <section
          ref={showcaseRef}
          css={showcaseSectionStyle}
          aria-label="완성된 네컷 그림 일기 예시"
        >
          <div css={showcaseStageStyle}>
            <div css={showcaseCopyStyle}>
              <div
                role="group"
                aria-label="현재 네컷을 만든 이야기"
                css={activeDiaryStoryStyle}
              >
                <p css={diaryCaptionStyle}>
                  {diaryExamples[activeDiaryIndex].caption}
                </p>
                <div css={progressStyle} aria-hidden="true">
                  {diaryExamples.map((diary, index) => (
                    <span
                      key={diary.alt}
                      css={progressSegmentStyle(index <= activeDiaryIndex)}
                    />
                  ))}
                </div>
              </div>
            </div>

            <div css={diaryStackStyle}>
              {diaryExamples.map((diary, index) => (
                <figure
                  key={diary.alt}
                  css={diaryStoryStyle(index, activeDiaryIndex)}
                  data-diary-active={index === activeDiaryIndex}
                >
                  <figcaption css={reducedDiaryCaptionStyle}>
                    {diary.caption}
                  </figcaption>
                  <div css={diaryFrameStyle}>
                    <img
                      src={diary.image}
                      alt={diary.alt}
                      loading={index === 0 ? 'eager' : 'lazy'}
                      decoding="async"
                      css={diaryImageStyle}
                    />
                  </div>
                </figure>
              ))}
            </div>
          </div>
        </section>

        <section
          ref={processRef}
          css={processSectionStyle}
          aria-labelledby="landing-process-title"
        >
          <header css={processHeadingStyle}>
            <div css={processHeadingCopyStyle}>
              <h2 id="landing-process-title" css={sectionTitleStyle}>
                이렇게 하루를 남겨요
              </h2>
            </div>
            <img
              src={writingScene}
              alt="사람이 강아지와 함께 오늘의 이야기를 기록하는 모습"
              loading="lazy"
              decoding="async"
              css={processIllustrationStyle}
            />
          </header>

          <ol css={processListStyle}>
            {processSteps.map((step, index) => (
              <li
                key={step.title}
                css={processStepStyle(index < visibleProcessStepCount, index)}
              >
                <span css={processStepNumberStyle} aria-hidden="true">
                  {String(index + 1).padStart(2, '0')}
                </span>
                <div css={processStepCopyStyle}>
                  <h3 css={processStepTitleStyle}>{step.title}</h3>
                  <p css={processStepDescriptionStyle}>{step.description}</p>
                </div>
              </li>
            ))}
          </ol>
        </section>
      </section>

      <section
        css={finalCtaSectionStyle(trialSection !== undefined)}
        aria-labelledby="landing-final-title"
      >
        <p css={finalEyebrowStyle}>이제, 당신의 차례예요</p>
        <div css={finalIllustrationFrameStyle}>
          <img
            src={emptyPersonAndDog}
            alt="사람과 강아지가 함께 새로운 네컷을 시작하는 모습"
            loading="lazy"
            decoding="async"
            css={finalIllustrationStyle}
          />
        </div>
        <h2 id="landing-final-title" css={finalTitleStyle}>
          재밌는 이야기를 만들어
          <br />
          친구에게 공유해보세요
        </h2>
        {finalAction}
      </section>
      {trialSection}
    </main>
  );
};

export default LandingPage;

const pageStyle = css`
  width: 100%;
  height: 100%;
  overflow-x: hidden;
  overflow-y: auto;
  background-color: #ffffff;
  color: ${theme.colors.text.primary};
  overscroll-behavior-y: contain;
  scrollbar-width: none;

  &::-webkit-scrollbar {
    display: none;
  }
`;

const topBarStyle = css`
  display: flex;
  align-items: center;
  width: 100%;
  height: 72px;
  padding: 0 24px;
  background-color: #ffffff;
`;

const logoStyle = css`
  display: block;
  width: 104px;
  height: 56px;
  object-fit: contain;
`;

const heroSectionStyle = css`
  position: relative;
  z-index: 2;
  display: flex;
  flex-direction: column;
  gap: 48px;
  padding: 32px 24px;
  background-color: #ffffff;
`;

const heroCopyStyle = css`
  display: flex;
  flex-direction: column;
  gap: 18px;
  margin: 0;
`;

const heroTitleStyle = css`
  margin: 0;
  color: ${theme.colors.text.primary};
  font-size: clamp(38px, 10.5vw, 46px);
  font-weight: 700;
  line-height: 1.24;
  letter-spacing: -1.4px;
  word-break: keep-all;
`;

const accentStyle = css`
  color: ${theme.colors.text.brand};
`;

const heroDescriptionStyle = css`
  max-width: 330px;
  margin: 0;
  color: ${theme.colors.text.secondary};
  font-size: 16px;
  font-weight: 400;
  line-height: 26px;
  word-break: keep-all;
`;

const heroIllustrationStyle = css`
  display: block;
  align-self: center;
  width: min(100%, 252px);
  max-height: 168px;
  object-fit: contain;
`;

const heroVisualStyle = css`
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 18px;
  width: 100%;
  margin-bottom: 0;
`;

const storyFlowStyle = css`
  position: relative;
  background-color: #f8f6ff;
`;

const showcaseSectionStyle = css`
  position: relative;
  height: 130vh;
  height: 130svh;
  border-top: 1px solid #efebfa;
  border-bottom: 1px solid #efebfa;
  background-color: #f8f6ff;

  @media (prefers-reduced-motion: reduce) {
    height: auto;
  }
`;

const showcaseStageStyle = css`
  position: sticky;
  top: 0;
  display: grid;
  width: 100%;
  height: 100vh;
  height: 100svh;
  grid-template-rows: auto auto;
  gap: 28px;
  align-content: start;
  align-items: stretch;
  padding: clamp(64px, 9.5svh, 80px) 24px 48px;
  overflow: hidden;
  background-color: #f8f6ff;

  @media (prefers-reduced-motion: reduce) {
    position: relative;
    height: auto;
    gap: 28px;
    padding: 64px 24px 56px;
    overflow: visible;
  }
`;

const showcaseCopyStyle = css`
  display: flex;
  flex-direction: column;
  gap: 8px;
`;

const diaryStackStyle = css`
  position: relative;
  align-self: center;
  width: 100%;
  max-width: 336px;
  aspect-ratio: 1;
  margin: 0 auto;

  @media (prefers-reduced-motion: reduce) {
    display: grid;
    max-width: none;
    grid-template-columns: 1fr;
    gap: 32px;
    aspect-ratio: auto;
    align-self: stretch;
    margin: 0;
  }
`;

const diaryStoryStyle = (index: number, activeIndex: number) => {
  const isActive = index === activeIndex;
  const isPast = index < activeIndex;
  const distance = Math.max(1, activeIndex - index);
  const pastDirection = index % 2 === 0 ? -1 : 1;
  const opacity = isActive ? 1 : isPast ? 0.7 : 0;
  const transform = isActive
    ? 'translate3d(0, 0, 0) scale(1) rotate(0deg)'
    : isPast
      ? `translate3d(${pastDirection * distance * 18}px, ${distance * -12}px, 0) scale(${1 - distance * 0.035}) rotate(${pastDirection * distance * 2.4}deg)`
      : 'translate3d(0, 52px, 0) scale(0.96) rotate(0deg)';
  const zIndex = isActive
    ? diaryExamples.length
    : isPast
      ? diaryExamples.length - distance
      : 0;

  return css`
    position: absolute;
    inset: 0;
    z-index: ${zIndex};
    margin: 0;
    opacity: ${opacity};
    transform: ${transform};
    transform-origin: 50% 88%;
    transition:
      opacity 420ms ease,
      transform 680ms cubic-bezier(0.22, 1, 0.36, 1);
    will-change: opacity, transform;

    @media (prefers-reduced-motion: reduce) {
      position: relative;
      inset: auto;
      display: grid;
      gap: 12px;
      opacity: 1;
      transform: none;
      transition: none;
      will-change: auto;
    }
  `;
};

const diaryFrameStyle = css`
  width: 100%;
  height: 100%;
  overflow: hidden;
  border: 1px solid #2b2a31;
  border-radius: 12px;
  background-color: ${theme.colors.text.primary};
  box-shadow: 0 18px 36px rgba(17, 17, 24, 0.12);

  @media (prefers-reduced-motion: reduce) {
    height: auto;
    aspect-ratio: 1;
  }
`;

const diaryImageStyle = css`
  display: block;
  width: 100%;
  height: 100%;
  object-fit: cover;
`;

const activeDiaryStoryStyle = css`
  display: flex;
  flex-direction: column;
  gap: 16px;
  width: 100%;

  @media (prefers-reduced-motion: reduce) {
    display: none;
  }
`;

const diaryCaptionStyle = css`
  margin: 0;
  color: ${theme.colors.text.primary};
  font-size: clamp(22px, 6.4vw, 28px);
  font-weight: 700;
  line-height: 1.38;
  letter-spacing: -0.6px;
  word-break: keep-all;
`;

const reducedDiaryCaptionStyle = css`
  display: none;
  margin: 0;
  color: ${theme.colors.text.primary};
  font-size: 19px;
  font-weight: 700;
  line-height: 28px;
  letter-spacing: -0.3px;
  word-break: keep-all;

  @media (prefers-reduced-motion: reduce) {
    display: block;
  }
`;

const progressStyle = css`
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 8px;
  width: 100%;

  @media (prefers-reduced-motion: reduce) {
    display: none;
  }
`;

const progressSegmentStyle = (isActive: boolean) => css`
  display: block;
  height: 3px;
  background-color: ${
    isActive ? theme.colors.text.primary : theme.colors.border.primary
  };
  transition: background-color 320ms ease;

  @media (prefers-reduced-motion: reduce) {
    transition: none;
  }
`;

const processSectionStyle = css`
  position: relative;
  z-index: 1;
  display: flex;
  flex-direction: column;
  gap: 28px;
  margin-top: clamp(-360px, -32svh, -240px);
  padding: 64px 24px 48px;
  border-radius: 24px 24px 0 0;
  background-color: #ffffff;

  @media (prefers-reduced-motion: reduce) {
    margin-top: -24px;
  }
`;

const processHeadingStyle = css`
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  gap: 16px;
  align-items: center;
`;

const processHeadingCopyStyle = css`
  display: flex;
  flex-direction: column;
  gap: 8px;
`;

const processIllustrationStyle = css`
  display: block;
  width: clamp(88px, 25vw, 104px);
  height: auto;
  object-fit: contain;
`;

const sectionTitleStyle = css`
  margin: 0;
  color: ${theme.colors.text.primary};
  font-size: 30px;
  font-weight: 700;
  line-height: 42px;
  letter-spacing: -0.8px;
  word-break: keep-all;
`;

const processListStyle = css`
  display: flex;
  flex-direction: column;
  margin: 0;
  padding: 0 18px;
  overflow: hidden;
  border: 1px solid #efebfa;
  border-radius: 20px;
  background-color: #fbfaff;
  list-style: none;
`;

const processStepStyle = (isVisible: boolean, index: number) => css`
  display: grid;
  grid-template-columns: 40px minmax(0, 1fr);
  gap: 8px;
  padding: 20px 0;
  border-top: ${
    index === 0 ? 'none' : `1px solid ${theme.colors.border.primary}`
  };
  opacity: ${isVisible ? 1 : 0};
  transform: translate3d(0, ${isVisible ? 0 : '22px'}, 0);
  transition:
    opacity 420ms ease ${index * 55}ms,
    transform 560ms cubic-bezier(0.22, 1, 0.36, 1) ${index * 55}ms;

  @media (prefers-reduced-motion: reduce) {
    opacity: 1;
    transform: none;
    transition: none;
  }
`;

const processStepNumberStyle = css`
  color: ${theme.colors.text.brand};
  font-size: 13px;
  font-weight: 700;
  line-height: 24px;
  letter-spacing: 0.2px;
`;

const processStepCopyStyle = css`
  display: flex;
  flex-direction: column;
  gap: 8px;
`;

const processStepTitleStyle = css`
  margin: 0;
  color: ${theme.colors.text.primary};
  font-size: 19px;
  font-weight: 700;
  line-height: 28px;
  letter-spacing: -0.3px;
  word-break: keep-all;
`;

const processStepDescriptionStyle = css`
  margin: 0;
  color: ${theme.colors.text.secondary};
  font-size: 15px;
  font-weight: 400;
  line-height: 24px;
  white-space: pre-line;
  word-break: keep-all;
`;

const finalCtaSectionStyle = (continuesToTrial: boolean) => css`
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 20px;
  padding: 44px 24px ${continuesToTrial ? '28px' : '56px'};
  border-top: 1px solid #efebfa;
  background-color: #f8f6ff;

  & > a {
    width: 100%;
  }
`;

const finalEyebrowStyle = css`
  margin: 0;
  color: ${theme.colors.text.brand};
  font-size: 13px;
  font-weight: 700;
  line-height: 20px;
  letter-spacing: 0.2px;
`;

const finalIllustrationFrameStyle = css`
  width: min(220px, 68vw);
  height: 148px;
  overflow: hidden;
`;

const finalIllustrationStyle = css`
  display: block;
  width: 100%;
  height: auto;
  transform: translateY(-23%);
`;

const finalTitleStyle = css`
  margin: 4px 0 8px;
  color: ${theme.colors.text.primary};
  font-size: clamp(31px, 8.2vw, 36px);
  font-weight: 700;
  line-height: 1.34;
  letter-spacing: -1px;
  text-align: center;
  word-break: keep-all;
`;
