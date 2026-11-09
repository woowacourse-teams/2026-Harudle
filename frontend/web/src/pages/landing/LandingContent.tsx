/** @jsxImportSource @emotion/react */
import { css, Global } from '@emotion/react';
import {
  useEffect,
  useRef,
  useState,
  type JSX,
  type MouseEvent,
  type ReactNode,
} from 'react';
import { Link } from 'react-router';
import harudleLogo from '../../assets/images/harudle-logo.webp';
import loginHero from '../../assets/images/login-shared-comic.png';
import heroVideo from './assets/landing-hero.mp4';
import heroPoster from './assets/landing-hero-poster.jpg';
import LandingLoginCta from './LandingLoginCta';
import LandingUsageStats from './LandingUsageStats';
import { theme } from '../../styles/theme';
import { LANDING_COPY } from './copy';
import LandingShowcase from './LandingShowcase';
import LandingShareSection from './LandingShareSection';
import LandingFeedPreview from './LandingFeedPreview';
import LandingPrivacy from './LandingPrivacy';
import LandingFaq from './LandingFaq';
import useLandingReveal from './useLandingReveal';
import useLandingStickyCta from './useLandingStickyCta';

const NAV_ITEMS = [
  { label: '하루들 소개', target: 'examples' },
  { label: '기록 보호', target: 'landing-privacy-title' },
  { label: '자주 묻는 질문', target: 'landing-faq-title' },
] as const;
interface LandingContentProps {
  trialSection: ReactNode;
  trialActionLabel?: string;
}

const LandingContent = ({
  trialSection,
  trialActionLabel = LANDING_COPY.hero.action,
}: LandingContentProps): JSX.Element => {
  const pageRef = useRef<HTMLElement>(null);
  const heroActionRef = useRef<HTMLAnchorElement>(null);
  const heroVideoRef = useRef<HTMLVideoElement>(null);
  const finalRef = useRef<HTMLElement>(null);
  const [menuOpen, setMenuOpen] = useState(false);
  const trialRef = useRef<HTMLDivElement>(null);
  useLandingReveal(pageRef);
  const showStickyCta = useLandingStickyCta(pageRef, heroActionRef, finalRef);

  useEffect(() => {
    const video = heroVideoRef.current;
    if (!video || typeof IntersectionObserver === 'undefined') return;

    const motion = window.matchMedia?.('(prefers-reduced-motion: reduce)');
    let visible = typeof IntersectionObserver === 'undefined';
    const updatePlayback = (): void => {
      if (motion?.matches || !visible) {
        video.pause();
        return;
      }
      void video.play().catch(() => {
        // 자동 재생이 차단되면 포스터 이미지를 그대로 보여준다.
      });
    };
    const observer =
      typeof IntersectionObserver === 'undefined'
        ? null
        : new IntersectionObserver(
            (entries) => {
              visible = entries.some((entry) => entry.isIntersecting);
              updatePlayback();
            },
            { root: pageRef.current, threshold: 0.05 },
          );
    observer?.observe(video);
    motion?.addEventListener('change', updatePlayback);
    updatePlayback();

    return () => {
      observer?.disconnect();
      motion?.removeEventListener('change', updatePlayback);
      video.pause();
    };
  }, []);

  useEffect(() => {
    if (!menuOpen) return;
    const closeOnEscape = (event: KeyboardEvent): void => {
      if (event.key === 'Escape') setMenuOpen(false);
    };
    window.addEventListener('keydown', closeOnEscape);
    return () => window.removeEventListener('keydown', closeOnEscape);
  }, [menuOpen]);

  const navigateTo = (
    event: MouseEvent<HTMLAnchorElement>,
    targetId: string,
  ): void => {
    const page = pageRef.current;
    const target = document.getElementById(targetId);
    if (!page || !target) return;
    event.preventDefault();
    const offset = window.innerWidth >= 768 ? 104 : 24;
    const reduceMotion = window.matchMedia?.(
      '(prefers-reduced-motion: reduce)',
    ).matches;
    page.scrollTo({
      top: Math.max(
        0,
        page.scrollTop +
          target.getBoundingClientRect().top -
          page.getBoundingClientRect().top -
          offset,
      ),
      behavior: reduceMotion ? 'instant' : 'smooth',
    });
    setMenuOpen(false);
  };

  useEffect(() => {
    const trial = trialRef.current;
    if (trial && document.activeElement === trial) {
      trial
        .querySelector<HTMLElement>('textarea, a, button')
        ?.focus({ preventScroll: true });
    }
  }, [trialSection]);

  const handleTrialClick = (): void => {
    const page = pageRef.current;
    const trial = trialRef.current;
    if (!page || !trial) return;
    const reduceMotion = window.matchMedia?.(
      '(prefers-reduced-motion: reduce)',
    ).matches;
    page.scrollTo({
      top: Math.max(
        0,
        page.scrollTop +
          trial.getBoundingClientRect().top -
          page.getBoundingClientRect().top -
          20,
      ),
      behavior: reduceMotion ? 'instant' : 'smooth',
    });
    (trial.querySelector<HTMLElement>('textarea, a, button') ?? trial).focus({
      preventScroll: true,
    });
    setMenuOpen(false);
  };

  return (
    <main ref={pageRef} css={pageStyle}>
      <Global styles={landingRootStyle} />
      <header css={headerStyle}>
        <div css={headerInnerStyle}>
          <a
            href="#top"
            css={logoLinkStyle}
            aria-label="하루들 처음으로"
            onClick={(event) => navigateTo(event, 'top')}
          >
            <img src={harudleLogo} alt="하루들" />
          </a>
          <nav css={desktopNavStyle} aria-label="랜딩 메뉴">
            {NAV_ITEMS.map((item) => (
              <a
                key={item.target}
                href={`#${item.target}`}
                onClick={(event) => navigateTo(event, item.target)}
              >
                {item.label}
              </a>
            ))}
          </nav>
          <div css={headerActionsStyle}>
            <Link to="/" css={headerCtaStyle}>
              하루들 시작하기
            </Link>
            <button
              type="button"
              css={menuButtonStyle}
              aria-label={menuOpen ? '메뉴 닫기' : '메뉴 열기'}
              aria-expanded={menuOpen}
              aria-controls="mobile-navigation"
              onClick={() => setMenuOpen((open) => !open)}
            >
              <span />
              <span />
            </button>
          </div>
        </div>
        <nav
          id="mobile-navigation"
          css={mobileNavStyle}
          aria-label="모바일 랜딩 메뉴"
          hidden={!menuOpen}
        >
          {NAV_ITEMS.map((item) => (
            <a
              key={item.target}
              href={`#${item.target}`}
              onClick={(event) => navigateTo(event, item.target)}
            >
              {item.label}
            </a>
          ))}
          <Link to="/">하루들 시작하기</Link>
        </nav>
      </header>

      <section
        id="top"
        css={heroSectionStyle}
        aria-labelledby="landing-hero-title"
      >
        <div css={heroVideoStyle} aria-hidden="true">
          <video
            ref={heroVideoRef}
            src={heroVideo}
            poster={heroPoster}
            muted
            loop
            playsInline
            preload="metadata"
          />
        </div>
        <div css={heroInnerStyle}>
          <div css={heroCopyStyle}>
            <h1 id="landing-hero-title">
              {LANDING_COPY.hero.titleLines[0]}
              <br />
              <span>{LANDING_COPY.hero.titleLines[1]}</span>
            </h1>
            <div css={heroActionsStyle}>
              <Link ref={heroActionRef} to="/" css={primaryButtonStyle}>
                하루들 시작하기
                <svg
                  width="20"
                  height="20"
                  viewBox="0 0 20 20"
                  fill="none"
                  aria-hidden="true"
                >
                  <path
                    d="M4 10h11m-4-4 4 4-4 4"
                    stroke="currentColor"
                    strokeWidth="1.8"
                    strokeLinecap="round"
                    strokeLinejoin="round"
                  />
                </svg>
              </Link>
              {trialSection !== null && (
                <button
                  type="button"
                  css={trialButtonStyle}
                  onClick={handleTrialClick}
                >
                  {trialActionLabel}
                </button>
              )}
            </div>
          </div>
        </div>
      </section>

      <LandingShowcase pageRef={pageRef} />
      <LandingShareSection pageRef={pageRef} />

      <section css={feedSectionStyle} aria-labelledby="landing-feed-title">
        <div css={feedInnerStyle}>
          <div css={feedIntroStyle}>
            <div data-reveal="feed-intro">
              <h2 id="landing-feed-title">{LANDING_COPY.feed.title}</h2>
            </div>
          </div>
          <div css={feedCardsStyle} data-reveal="feed-preview">
            <LandingFeedPreview />
            <Link to="/" css={feedLinkStyle}>
              {LANDING_COPY.feed.action}
            </Link>
            <LandingUsageStats pageRef={pageRef} />
          </div>
        </div>
      </section>

      <LandingPrivacy />
      <LandingFaq />

      <section
        ref={finalRef}
        css={finalSectionStyle}
        aria-labelledby="landing-final-title"
      >
        <div css={finalInnerStyle}>
          <div css={finalCopyStyle} data-reveal="final-copy">
            <h2 id="landing-final-title">{LANDING_COPY.final.title}</h2>
            {trialSection !== null ? (
              <>
                <div
                  ref={trialRef}
                  id="landing-trial"
                  tabIndex={-1}
                  css={trialTargetStyle}
                >
                  {trialSection}
                </div>
                <div css={continueLoginStyle}>
                  <span>{LANDING_COPY.final.loginPrompt}</span>
                  <LandingLoginCta
                    label={LANDING_COPY.final.loginAction}
                    appearance="text"
                    analyticsEvent="landing_direct_login_clicked"
                    location="final"
                  />
                </div>
              </>
            ) : (
              <Link to="/" css={primaryButtonStyle}>
                하루들 시작하기
              </Link>
            )}
          </div>
          <div css={finalVisualStyle} data-reveal="illustration">
            <div css={finalHaloStyle} aria-hidden="true" />
            <img
              src={loginHero}
              alt="두 사람이 강아지와 함께 네컷만화를 보는 모습"
              loading="lazy"
            />
          </div>
        </div>
      </section>

      {showStickyCta && (
        <aside css={stickyCtaStyle} aria-label="빠른 서비스 이동">
          <Link to="/">하루들 시작하기</Link>
        </aside>
      )}
      <footer css={footerStyle}>
        <div css={footerInnerStyle}>
          <div css={footerBrandStyle}>
            <strong>하루들</strong>
            <small>© 2026 Harudle</small>
          </div>
          <nav css={footerLinksStyle} aria-label="서비스 안내">
            {LANDING_COPY.footer.links.map((link) => (
              <a
                key={link.label}
                href={link.href}
                target={link.href.startsWith('https://') ? '_blank' : undefined}
                rel={
                  link.href.startsWith('https://')
                    ? 'noopener noreferrer'
                    : undefined
                }
              >
                {link.label}
              </a>
            ))}
          </nav>
        </div>
      </footer>
    </main>
  );
};

export default LandingContent;

const landingRootStyle = css`
  #root {
    max-width: none;
    box-shadow: none;
  }
  #root > div {
    max-width: none;
  }
`;

const pageStyle = css`
  width: 100%;
  height: 100%;
  overflow-x: hidden;
  overflow-y: auto;
  background-color: ${theme.colors.background.surface};
  color: ${theme.colors.foreground.neutral};
  overscroll-behavior-y: contain;
  font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', sans-serif;

  button {
    font-family: inherit;
  }

  &[data-motion='enabled'] [data-reveal] {
    opacity: 0;
    transform: translate3d(0, 16px, 0);
    transition:
      opacity 400ms ease-out,
      transform 400ms ease-out;
  }

  &[data-motion='enabled'] [data-revealed='true'],
  &[data-motion='enabled'] [data-reveal]:focus-within {
    opacity: 1;
    transform: translate3d(0, 0, 0);
  }

  &[data-motion='enabled'] [data-comic-mask] {
    opacity: 1;
  }
  &[data-motion='enabled'] [data-revealed='true'] [data-comic-mask],
  &[data-motion='enabled'] [data-demo-stage='complete'] [data-comic-mask] {
    animation: landing-unmask 0.35s ease-out both;
    animation-delay: calc(var(--panel-index) * 0.15s);
  }
  &[data-motion='enabled'] [data-demo-stage='complete'] [data-comic-mask] {
    animation-delay: calc(0.2s + var(--panel-index) * 0.15s);
  }
  &[data-motion='enabled'] [data-revealed='true'][data-reveal='comic'] {
    animation: landing-settle 0.65s ease-out both;
  }
  &[data-motion='enabled'] [data-chat-step] {
    opacity: 0;
  }
  &[data-motion='enabled']
    [data-revealed='true']
    [data-chat-step]:not([data-chat-step='4']) {
    animation: landing-bubble 0.35s ease-out both;
    animation-delay: calc(0.25s + (var(--chat-step) - 1) * 0.65s);
  }
  &[data-motion='enabled'] [data-revealed='true'] [data-chat-step='4'] {
    animation: landing-typing 0.8s 2.1s linear both;
  }
  &[data-motion='enabled'] [data-revealed='true'] [data-chat-step='4'] span {
    animation-play-state: running;
  }
  [data-chat-step='1'] {
    --chat-step: 1;
  }
  [data-chat-step='2'] {
    --chat-step: 2;
  }
  [data-chat-step='3'] {
    --chat-step: 3;
  }
  [data-chat-step='reactions'] {
    --chat-step: 3.8;
  }
  [data-chat-step='5'] {
    --chat-step: 5.2;
  }
  @keyframes landing-unmask {
    from {
      opacity: 1;
    }
    to {
      opacity: 0;
    }
  }
  @keyframes landing-bubble {
    from {
      opacity: 0;
      transform: translateY(8px);
    }
    to {
      opacity: 1;
      transform: none;
    }
  }
  @keyframes landing-settle {
    from {
      opacity: 0;
      transform: translateY(16px) rotate(-3deg);
    }
    to {
      opacity: 1;
      transform: none;
    }
  }
  @keyframes landing-typing {
    0%,
    90% {
      opacity: 1;
      visibility: visible;
    }
    100% {
      opacity: 0;
      visibility: hidden;
    }
  }

  @media (prefers-reduced-motion: reduce) {
    &[data-motion='enabled'] [data-reveal] {
      opacity: 1;
      transform: none;
      transition: none;
      animation: none;
    }
    [data-comic-mask] {
      opacity: 0 !important;
      animation: none !important;
    }
    [data-chat-step]:not([data-chat-step='4']) {
      opacity: 1 !important;
      transform: none !important;
      animation: none !important;
    }
    [data-chat-step='4'] {
      display: none;
    }
  }
`;

const headerStyle = css`
  position: sticky;
  z-index: 20;
  top: 0;
  background: rgb(255 255 255 / 92%);
  border-bottom: 1px solid rgb(233 228 242 / 60%);
  backdrop-filter: blur(18px);
  @media (max-width: 767px) {
    position: relative;
    background: #fff;
    backdrop-filter: none;
  }
`;
const headerInnerStyle = css`
  display: flex;
  align-items: center;
  gap: 32px;
  width: min(100%, 1360px);
  height: 80px;
  margin: 0 auto;
  padding: 0 clamp(24px, 4vw, 56px);
  @media (max-width: 767px) {
    height: 70px;
    padding: 0 20px;
    gap: 12px;
  }
`;
const logoLinkStyle = css`
  display: flex;
  flex: 0 0 auto;
  align-items: center;
  width: 118px;
  height: 62px;
  img {
    width: 100%;
    height: 100%;
    object-fit: contain;
  }
  &:focus-visible {
    outline: 3px solid ${theme.colors.stroke.focusRing};
    outline-offset: 2px;
    border-radius: 8px;
  }
  @media (max-width: 767px) {
    width: 100px;
    height: 54px;
    margin-left: -7px;
  }
`;
const desktopNavStyle = css`
  display: flex;
  align-items: center;
  justify-content: center;
  gap: clamp(24px, 3vw, 46px);
  flex: 1;
  a {
    padding: 12px 2px;
    color: #5f566b;
    font-size: 14px;
    font-weight: 700;
    line-height: 20px;
    text-decoration: none;
    transition: color 160ms ease;
  }
  a:hover {
    color: #7355da;
  }
  a:focus-visible {
    outline: 3px solid ${theme.colors.stroke.focusRing};
    outline-offset: 3px;
    border-radius: 5px;
  }
  @media (max-width: 767px) {
    display: none;
  }
`;
const headerActionsStyle = css`
  display: flex;
  align-items: center;
  gap: 10px;
  margin-left: auto;
`;
const headerCtaStyle = css`
  display: inline-flex;
  align-items: center;
  justify-content: center;
  min-height: 44px;
  padding: 10px 18px;
  border-radius: 999px;
  background: #211831;
  color: #fff;
  font-size: 13px;
  font-weight: 700;
  text-decoration: none;
  transition:
    background-color 160ms ease,
    transform 160ms ease;
  &:hover {
    background: #7355da;
  }
  &:focus-visible {
    outline: 3px solid ${theme.colors.stroke.focusRing};
    outline-offset: 3px;
  }
  @media (max-width: 767px) {
    min-height: 40px;
    padding: 8px 14px;
    font-size: 12px;
  }
  @media (max-width: 360px) {
    display: none;
  }
  @media (prefers-reduced-motion: reduce) {
    transition: none;
  }
`;
const menuButtonStyle = css`
  display: none;
  width: 42px;
  height: 42px;
  padding: 0;
  border: 0;
  border-radius: 50%;
  background: #f5f2fb;
  color: #211831;
  cursor: pointer;
  span {
    display: block;
    width: 17px;
    height: 2px;
    margin: 4px auto;
    border-radius: 3px;
    background: currentColor;
    transition: transform 180ms ease;
  }
  &[aria-expanded='true'] span:first-of-type {
    transform: translateY(3px) rotate(45deg);
  }
  &[aria-expanded='true'] span:last-of-type {
    transform: translateY(-3px) rotate(-45deg);
  }
  &:focus-visible {
    outline: 3px solid ${theme.colors.stroke.focusRing};
    outline-offset: 3px;
  }
  @media (max-width: 767px) {
    display: block;
  }
  @media (prefers-reduced-motion: reduce) {
    span {
      transition: none;
    }
  }
`;
const mobileNavStyle = css`
  position: absolute;
  z-index: 21;
  top: calc(100% - 1px);
  left: 12px;
  right: 12px;
  display: grid;
  gap: 2px;
  padding: 10px;
  border: 1px solid #eee9f7;
  border-radius: 20px;
  background: #fff;
  box-shadow: 0 18px 50px rgb(43 29 76 / 13%);
  &[hidden] {
    display: none;
  }
  a {
    padding: 14px 16px;
    border-radius: 12px;
    color: #352b43;
    font-size: 14px;
    font-weight: 700;
    line-height: 20px;
    text-decoration: none;
  }
  a:focus-visible {
    outline: 3px solid ${theme.colors.stroke.focusRing};
    outline-offset: -2px;
  }
  a:last-of-type {
    color: #7355da;
    background: #f7f4ff;
  }
  @media (min-width: 768px) {
    display: none;
  }
`;
const heroSectionStyle = css`
  position: relative;
  overflow: hidden;
  width: min(calc(100% - 40px), 1400px);
  min-height: clamp(620px, calc(100vh - 104px), 780px);
  margin: 10px auto 32px;
  border: 1px solid #eee5d8;
  border-radius: 36px;
  background: #f2ebde;
  @media (max-width: 900px) {
    min-height: 680px;
  }
  @media (max-width: 767px) {
    width: calc(100% - 24px);
    min-height: 620px;
    margin-top: 0;
    margin-bottom: 24px;
    border-radius: 26px;
  }
`;
const heroVideoStyle = css`
  position: absolute;
  inset: 0;
  background: #f2ebde url(${heroPoster}) right center / cover no-repeat;
  video {
    display: block;
    width: 100%;
    height: 100%;
    object-fit: cover;
    object-position: right center;
  }
  &::after {
    position: absolute;
    inset: 0;
    background: linear-gradient(90deg, rgb(249 246 255 / 24%), transparent 55%);
    content: '';
    pointer-events: none;
  }
  @media (max-width: 900px) {
    top: auto;
    height: 62%;
    background-position: right bottom;
    video {
      object-position: right bottom;
    }
    &::after {
      background: linear-gradient(180deg, #f2ebde 0%, transparent 12%);
    }
  }
  @media (prefers-reduced-motion: reduce) {
    video {
      visibility: hidden;
    }
  }
`;
const heroInnerStyle = css`
  position: relative;
  z-index: 1;
  display: flex;
  align-items: center;
  width: min(100%, 1250px);
  min-height: inherit;
  margin: 0 auto;
  padding: 54px clamp(40px, 5vw, 74px);
  @media (max-width: 900px) {
    align-items: flex-start;
  }
  @media (max-width: 767px) {
    padding: 48px 22px 10px;
  }
`;
const heroCopyStyle = css`
  position: relative;
  z-index: 3;
  min-width: 0;
  width: min(100%, 580px);
  animation: hero-copy-in 700ms cubic-bezier(0.2, 0.7, 0.2, 1) both;
  h1 {
    margin: 0;
    color: #1c1530;
    font-size: clamp(50px, 5.5vw, 78px);
    font-weight: 800;
    line-height: 1.17;
    letter-spacing: -0.065em;
    word-break: keep-all;
  }
  h1 span {
    color: #7355da;
  }
  @keyframes hero-copy-in {
    from {
      opacity: 0;
      transform: translateY(16px);
    }
    to {
      opacity: 1;
      transform: none;
    }
  }
  @media (max-width: 767px) {
    h1 {
      font-size: clamp(38px, 10vw, 48px);
      line-height: 1.22;
    }
  }
  @media (prefers-reduced-motion: reduce) {
    animation: none;
  }
`;
const primaryButtonStyle = css`
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 28px;
  min-height: 58px;
  margin-top: 32px;
  padding: 15px 23px 15px 27px;
  border-radius: 18px;
  background: #7355da;
  color: #fff;
  box-shadow: 0 12px 25px rgb(115 85 218 / 20%);
  font-size: 15px;
  font-weight: 800;
  line-height: 24px;
  text-decoration: none;
  transition:
    background-color 180ms ease,
    transform 180ms ease,
    box-shadow 180ms ease;
  &:hover {
    background: #6242c5;
    box-shadow: 0 15px 30px rgb(115 85 218 / 28%);
    transform: translateY(-2px);
  }
  &:focus-visible {
    outline: 3px solid ${theme.colors.stroke.focusRing};
    outline-offset: 4px;
  }
  @media (max-width: 767px) {
    width: 100%;
    min-height: 56px;
    margin-top: 25px;
  }
  @media (prefers-reduced-motion: reduce) {
    transition: none;
    &:hover {
      transform: none;
    }
  }
`;
const feedSectionStyle = css`
  padding: clamp(90px, 10vw, 150px) 24px;
  background: #fbfaff;
  @media (max-width: 767px) {
    padding: 84px 20px;
  }
`;
const feedInnerStyle = css`
  display: grid;
  grid-template-columns: minmax(0, 0.75fr) minmax(0, 1.25fr);
  align-items: center;
  gap: clamp(48px, 7vw, 110px);
  width: min(100%, 1180px);
  margin: 0 auto;
  @media (max-width: 767px) {
    grid-template-columns: 1fr;
    gap: 34px;
  }
`;
const feedIntroStyle = css`
  min-width: 0;
  h2 {
    margin: 0;
    color: #1c1530;
    font-size: clamp(36px, 4.5vw, 58px);
    font-weight: 800;
    line-height: 1.24;
    letter-spacing: -0.05em;
    white-space: pre-line;
    word-break: keep-all;
  }
  @media (max-width: 767px) {
    h2 {
      font-size: clamp(30px, 8vw, 40px);
    }
  }
`;
const feedCardsStyle = css`
  min-width: 0;
`;
const finalSectionStyle = css`
  overflow: hidden;
  padding: clamp(80px, 8vw, 125px) 24px;
  background: #f5f1ff;
  @media (max-width: 767px) {
    padding: 80px 20px 74px;
  }
`;
const finalInnerStyle = css`
  display: grid;
  grid-template-columns: minmax(0, 0.9fr) minmax(0, 1.1fr);
  align-items: center;
  gap: 42px;
  width: min(100%, 1180px);
  margin: 0 auto;
  @media (max-width: 767px) {
    display: flex;
    flex-direction: column-reverse;
    gap: 28px;
  }
`;
const finalCopyStyle = css`
  h2 {
    margin: 0;
    color: #1c1530;
    font-size: clamp(36px, 4.5vw, 58px);
    font-weight: 800;
    line-height: 1.24;
    letter-spacing: -0.05em;
    white-space: pre-line;
    word-break: keep-all;
  }
  @media (max-width: 767px) {
    width: 100%;
    h2 {
      font-size: clamp(30px, 8vw, 42px);
    }
  }
`;
const finalVisualStyle = css`
  position: relative;
  display: grid;
  place-items: center;
  width: 100%;
  min-width: 0;
  min-height: 390px;
  img {
    position: relative;
    z-index: 1;
    display: block;
    width: min(100%, 590px);
    height: auto;
    filter: drop-shadow(0 20px 35px rgb(86 61 147 / 11%));
  }
  @media (max-width: 767px) {
    min-height: 0;
    img {
      width: min(100%, 400px);
    }
  }
`;
const finalHaloStyle = css`
  position: absolute;
  width: 68%;
  aspect-ratio: 1;
  border-radius: 50%;
  background: radial-gradient(
    circle,
    #e3d5ff 0%,
    rgb(227 213 255 / 25%) 65%,
    transparent 72%
  );
`;
const stickyCtaStyle = css`
  position: fixed;
  z-index: 15;
  right: 0;
  bottom: 0;
  left: 0;
  display: none;
  padding: 10px 20px max(10px, env(safe-area-inset-bottom));
  background: linear-gradient(0deg, #fff 58%, rgb(255 255 255 / 92%));
  a {
    display: flex;
    align-items: center;
    justify-content: center;
    min-height: 52px;
    border-radius: 16px;
    background: #7355da;
    color: #fff;
    font-size: 14px;
    font-weight: 800;
    text-decoration: none;
    box-shadow: 0 8px 22px rgb(115 85 218 / 18%);
  }
  a:focus-visible {
    outline: 3px solid ${theme.colors.stroke.focusRing};
    outline-offset: 3px;
  }
  @media (max-width: 767px) {
    display: block;
  }
`;
const footerStyle = css`
  padding: 52px 24px calc(52px + env(safe-area-inset-bottom));
  border-top: 1px solid #ece8f1;
  background: #fff;
  color: #81798b;
  @media (max-width: 767px) {
    padding: 38px 20px calc(40px + env(safe-area-inset-bottom));
  }
`;
const footerInnerStyle = css`
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 30px;
  width: min(100%, 1180px);
  margin: 0 auto;
  @media (max-width: 767px) {
    flex-direction: column;
    gap: 24px;
  }
`;
const footerBrandStyle = css`
  strong {
    color: #20182d;
    font-size: 18px;
    font-weight: 800;
  }
  small {
    display: block;
    margin-top: 16px;
    color: #aaa3b1;
    font-size: 11px;
  }
`;
const footerLinksStyle = css`
  display: flex;
  flex-wrap: wrap;
  gap: 12px 22px;
  padding-top: 4px;
  a {
    color: #777081;
    font-size: 12px;
    line-height: 20px;
    text-decoration: none;
  }
  a:hover {
    color: #7355da;
  }
  a:focus-visible {
    outline: 3px solid ${theme.colors.stroke.focusRing};
    outline-offset: 3px;
  }
`;

const trialButtonStyle = css`
  display: block;
  width: 100%;
  min-height: 52px;
  margin-top: 10px;
  border: 1px solid #8b6fe8;
  border-radius: 14px;
  background: white;
  color: #6545cb;
  font-size: 16px;
  font-weight: 700;
  cursor: pointer;
`;
const heroActionsStyle = css`
  display: grid;
  width: min(100%, 260px);
  @media (max-width: 767px) {
    width: 100%;
  }
`;
const trialTargetStyle = css`
  width: min(100%, 440px);
  text-align: left;
  &:focus {
    outline: none;
  }
`;
const continueLoginStyle = css`
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
  margin-top: 20px;
  font-size: 14px;
`;
const feedLinkStyle = css`
  display: inline-block;
  margin: 16px 0;
  color: #6545cb;
  font-weight: 700;
`;
