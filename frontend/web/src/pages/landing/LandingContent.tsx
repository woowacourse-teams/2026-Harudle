import { useEffect, useRef, useState, type JSX, type ReactNode } from 'react';
import { css, type SerializedStyles } from '@emotion/react';
import harudleLogo from '../../assets/images/harudle-logo.png';
import loginHero from '../../assets/images/login-shared-comic.png';
import chatMeImage from './assets/chat-me.png';
import chatFriendImage from './assets/chat-friend.png';
import { theme } from '../../styles/theme';
import LandingLoginCta from './LandingLoginCta';
import { LANDING_COPY, LANDING_EXAMPLES } from './copy';
import LandingComicPreview from './LandingComicPreview';
import useLandingReveal from './useLandingReveal';
import LandingExampleDemo from './LandingExampleDemo';
import useLandingStickyCta from './useLandingStickyCta';
import LandingUsageStats from './LandingUsageStats';
import LandingSlackCommunity from './LandingSlackCommunity';

interface LandingContentProps {
  trialSection: ReactNode;
  trialActionLabel?: string;
}

const LandingContent = ({
  trialSection,
  trialActionLabel = LANDING_COPY.hero.action,
}: LandingContentProps): JSX.Element => {
  const pageRef = useRef<HTMLElement>(null);
  useLandingReveal(pageRef);
  const trialRef = useRef<HTMLDivElement>(null);
  const exampleRef = useRef<HTMLElement>(null);
  const [showScrollHint, setShowScrollHint] = useState(true);
  const heroActionRef = useRef<HTMLButtonElement>(null);
  const finalRef = useRef<HTMLElement>(null);
  const showStickyCta = useLandingStickyCta(pageRef, heroActionRef, finalRef);
  const [activeExample, setActiveExample] = useState(0);
  const example = LANDING_EXAMPLES[activeExample];

  useEffect(() => {
    const trial = trialRef.current;
    // 준비 중 CTA로 이동한 사용자는 입력창이 준비되면 그대로 작성을 시작한다.
    if (trial && document.activeElement === trial) {
      trial
        .querySelector<HTMLElement>('textarea, a, button')
        ?.focus({ preventScroll: true });
    }
  }, [trialSection]);

  const scrollWithinLanding = (target: HTMLElement | null): void => {
    const page = pageRef.current;
    if (!page || !target) return;
    const prefersReducedMotion = window.matchMedia?.(
      '(prefers-reduced-motion: reduce)',
    ).matches;
    // scrollIntoView는 문서까지 함께 움직일 수 있으므로 내부 스크롤만 지정한다.
    page.scrollTo({
      top:
        page.scrollTop +
        target.getBoundingClientRect().top -
        page.getBoundingClientRect().top -
        20,
      behavior: prefersReducedMotion ? 'instant' : 'smooth',
    });
  };

  const handleTrialClick = (): void => {
    const trial = trialRef.current;
    scrollWithinLanding(trial);
    const focusTarget = trial?.querySelector<HTMLElement>(
      'textarea, a, button',
    );
    (focusTarget ?? trial)?.focus({ preventScroll: true });
  };

  const handleExampleClick = (): void => {
    scrollWithinLanding(exampleRef.current);
    exampleRef.current
      ?.querySelector<HTMLButtonElement>('button')
      ?.focus({ preventScroll: true });
  };

  return (
    <main
      ref={pageRef}
      css={pageStyle}
      onScroll={(event): void =>
        setShowScrollHint(event.currentTarget.scrollTop < 40)
      }
    >
      <header css={headerStyle}>
        <img src={harudleLogo} alt="하루들" css={logoStyle} />
        <LandingLoginCta
          label="로그인"
          appearance="text"
          analyticsEvent="landing_direct_login_clicked"
          location="hero"
        />
      </header>

      <section css={heroStyle} aria-labelledby="landing-hero-title">
        <div>
          <h1 id="landing-hero-title" css={heroTitleStyle}>
            {LANDING_COPY.hero.titleLines[0]}
            <br />
            <span>{LANDING_COPY.hero.titleLines[1]}</span>
          </h1>
        </div>
        <p css={[descriptionStyle, heroDescriptionStyle]}>
          {LANDING_COPY.hero.description}
        </p>
        <div css={heroActionStyle}>
          <button
            ref={heroActionRef}
            type="button"
            css={primaryButtonStyle}
            onClick={handleTrialClick}
          >
            {trialActionLabel} <span aria-hidden="true">→</span>
          </button>
          <p css={noticeStyle}>{LANDING_COPY.hero.trialNotice}</p>
        </div>
        <figure data-reveal="comic" css={heroPreviewStyle}>
          <LandingComicPreview example={LANDING_EXAMPLES[1]} />
          <figcaption css={previewCaptionStyle}>
            {LANDING_COPY.hero.previewCaption}
          </figcaption>
        </figure>
        {showScrollHint && (
          <button
            type="button"
            aria-label={LANDING_COPY.hero.scrollHintLabel}
            css={scrollHintStyle}
            onClick={handleExampleClick}
          >
            <svg
              aria-hidden="true"
              width="24"
              height="24"
              viewBox="0 0 24 24"
              fill="none"
              css={scrollArrowStyle}
            >
              <path
                d="M12 4v16m-7-7 7 7 7-7"
                stroke="currentColor"
                strokeWidth="2.5"
                strokeLinecap="round"
                strokeLinejoin="round"
              />
            </svg>
          </button>
        )}
      </section>

      <section
        ref={exampleRef}
        css={exampleSectionStyle}
        aria-labelledby="landing-example-title"
      >
        <h2 id="landing-example-title" css={sectionTitleStyle}>
          {LANDING_COPY.examples.title}
        </h2>
        <p id="landing-example-choice-hint" css={exampleChoiceHintStyle}>
          {LANDING_COPY.examples.choiceHint}
        </p>
        <div
          css={exampleChoicesStyle}
          role="group"
          aria-describedby="landing-example-choice-hint"
          aria-label="네컷만화 예시 선택"
        >
          {LANDING_EXAMPLES.map((item, index) => (
            <button
              key={item.label}
              type="button"
              aria-pressed={activeExample === index}
              aria-controls="landing-example"
              css={exampleChoiceStyle(activeExample === index)}
              onClick={() => setActiveExample(index)}
            >
              {item.label}
              <span aria-hidden="true" css={exampleChoiceIconStyle}>
                {activeExample === index ? '✓' : '›'}
              </span>
            </button>
          ))}
        </div>
        <LandingExampleDemo
          key={example.id}
          example={example}
          pageRef={pageRef}
        />
      </section>

      <section css={sectionStyle} aria-labelledby="landing-share-title">
        <h2 id="landing-share-title" css={sectionTitleStyle}>
          {LANDING_COPY.share.title}
        </h2>
        <figure
          data-reveal="chat"
          css={shareSceneStyle}
          aria-label="공유 장면 예시"
        >
          <figcaption css={chatHeaderStyle}>
            <span aria-hidden="true" css={chatNavigationIconStyle}>
              ‹
            </span>
            <strong>{LANDING_COPY.share.sceneTitle}</strong>
            <span aria-hidden="true" css={chatNavigationIconStyle}>
              ≡
            </span>
          </figcaption>
          <p css={chatSceneLabelStyle}>
            {LANDING_COPY.share.sceneLabel} · {LANDING_COPY.share.dateLabel}
          </p>
          <div css={chatBodyStyle}>
            <div data-chat-step="1" css={sentRowStyle}>
              <div css={sentContentStyle}>
                <span css={chatNameStyle}>{LANDING_COPY.share.senderName}</span>
                <p css={sentMessageStyle}>{LANDING_COPY.share.sceneMessage}</p>
              </div>
              <img src={chatMeImage} alt="나의 프로필" css={avatarStyle} />
            </div>
            <div data-chat-step="2" css={receivedRowStyle}>
              <img
                src={chatFriendImage}
                alt="친구의 프로필"
                css={avatarStyle}
              />
              <div css={receivedContentStyle}>
                <span css={chatNameStyle}>
                  {LANDING_COPY.share.recipientName}
                </span>
                <p css={replyMessageStyle}>{LANDING_COPY.share.sceneReply}</p>
              </div>
            </div>
            <p data-chat-step="2" css={followUpMessageStyle}>
              {LANDING_COPY.share.sceneFollowUp}
            </p>
            <img
              data-chat-step="3"
              src={LANDING_EXAMPLES[0].imageUrl}
              alt={`${LANDING_EXAMPLES[0].title} 네컷만화`}
              loading="lazy"
              decoding="async"
              css={sharedImageStyle}
            />
            <ul
              data-chat-step="reactions"
              css={imageReactionsStyle}
              aria-label="이미지에 달린 반응"
            >
              {LANDING_COPY.share.imageReactions.map(
                (reaction): JSX.Element => (
                  <li
                    key={reaction.emoji}
                    aria-label={`${reaction.label} ${reaction.count}명`}
                  >
                    <span aria-hidden="true" css={reactionEmojiStyle}>
                      {reaction.emoji}
                    </span>
                    <span aria-hidden="true">{reaction.count}</span>
                  </li>
                ),
              )}
            </ul>
            <p data-chat-step="4" css={typingStyle} aria-hidden="true">
              •••
            </p>
            <div data-chat-step="5" css={sentRowStyle}>
              <p css={sentMessageStyle}>{LANDING_COPY.share.sceneReaction}</p>
              <img src={chatMeImage} alt="나의 프로필" css={avatarStyle} />
            </div>
          </div>
          <div css={chatComposerStyle} aria-hidden="true">
            <span css={chatAddStyle}>＋</span>
            <span css={chatInputStyle}>
              {LANDING_COPY.share.inputPlaceholder}
              <span>☺</span>
            </span>
          </div>
        </figure>
        <div css={communityIntroStyle}>
          <h3 css={sectionTitleStyle}>{LANDING_COPY.share.communityTitle}</h3>
          <p css={descriptionStyle}>{LANDING_COPY.share.communityNotice}</p>
        </div>
        <LandingSlackCommunity pageRef={pageRef} />
        <LandingUsageStats pageRef={pageRef} />
      </section>

      <section
        ref={finalRef}
        css={finalSectionStyle}
        aria-labelledby="landing-final-title"
      >
        <img
          src={loginHero}
          alt="두 사람이 네컷만화를 함께 보는 모습"
          loading="lazy"
          data-reveal="illustration"
          css={finalImageStyle}
        />
        <h2 id="landing-final-title" css={sectionTitleStyle}>
          {LANDING_COPY.final.title}
        </h2>
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
      </section>
      {showStickyCta && (
        <aside css={stickyCtaStyle} aria-label="빠른 체험">
          <button
            type="button"
            css={primaryButtonStyle}
            onClick={handleTrialClick}
          >
            {trialActionLabel} <span aria-hidden="true">→</span>
          </button>
        </aside>
      )}
      <footer css={footerStyle}>
        <p css={footerBrandStyle}>
          <strong>{LANDING_COPY.footer.brand}</strong>
          <span>{LANDING_COPY.footer.tagline}</span>
        </p>
        <nav css={footerLinksStyle} aria-label="서비스 안내">
          {LANDING_COPY.footer.links.map((link): JSX.Element => (
            <a
              key={link.label}
              href={link.href}
              target="_blank"
              rel="noopener noreferrer"
            >
              {link.label}
            </a>
          ))}
        </nav>
        <p css={copyrightStyle}>{LANDING_COPY.footer.copyright}</p>
      </footer>
    </main>
  );
};

export default LandingContent;

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
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 16px 24px;
`;

const logoStyle = css`
  width: 96px;
  height: 44px;
  object-fit: contain;
`;

const sectionStyle = css`
  display: flex;
  flex-direction: column;
  gap: 16px;
  padding: 64px 24px;
`;

const heroStyle = css`
  ${sectionStyle};
  gap: 18px;
  padding-top: 24px;
  padding-bottom: 56px;
`;

const heroTitleStyle = css`
  margin: 0;
  font-size: clamp(32px, 9vw, 40px);
  font-weight: 800;
  line-height: 1.28;
  letter-spacing: -0.02em;
  word-break: keep-all;

  span {
    color: ${theme.colors.foreground.brand};
  }
`;

const descriptionStyle = css`
  margin: 0;
  color: ${theme.colors.foreground.neutralMuted};
  font-size: 15px;
  line-height: 26px;
  word-break: keep-all;
`;

const heroDescriptionStyle = css`
  white-space: pre-line;
`;

const communityIntroStyle = css`
  display: grid;
  gap: 12px;
  margin-top: 40px;
`;

const heroActionStyle = css`
  display: grid;
  gap: 10px;
  margin-top: 6px;
  text-align: center;
`;

const primaryButtonStyle = css`
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 16px;
  width: 100%;
  min-height: 56px;
  padding: 14px 20px;
  border: none;
  border-radius: 16px;
  background-color: ${theme.colors.background.brandStrong};
  color: ${theme.colors.foreground.onBrand};
  font-size: 16px;
  font-weight: 700;
  line-height: 24px;
  cursor: pointer;

  &:focus-visible {
    outline: 3px solid ${theme.colors.stroke.focusRing};
    outline-offset: 3px;
  }
`;

const noticeStyle = css`
  margin: 0;
  color: ${theme.colors.foreground.neutralMuted};
  font-size: 12px;
  line-height: 20px;
  word-break: keep-all;
`;

const heroPreviewStyle = css`
  margin: 0 4px;
  padding: 10px 10px 0;
  border: 1px solid ${theme.colors.stroke.brandWeak};
  border-radius: 20px;
  background-color: ${theme.colors.background.brandWeak};
`;

const previewCaptionStyle = css`
  padding: 14px 4px;
  font-size: 13px;
  line-height: 22px;
  text-align: center;
`;

const exampleSectionStyle = css`
  ${sectionStyle};
  background-color: ${theme.colors.background.brandWeak};
`;

const sectionTitleStyle = css`
  margin: 0;
  font-size: clamp(25px, 7.4vw, 30px);
  font-weight: 800;
  line-height: 1.4;
  letter-spacing: -0.02em;
  white-space: pre-line;
  word-break: keep-all;
`;

const exampleChoiceHintStyle = css`
  margin: 4px 0 -8px;
  color: ${theme.colors.foreground.neutralMuted};
  font-size: 12px;
  line-height: 20px;
`;

const exampleChoicesStyle = css`
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 8px;
  padding-bottom: 3px;
`;

const exampleChoiceStyle = (active: boolean): SerializedStyles => css`
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 6px;
  min-height: 48px;
  padding: 10px 6px;
  border: 1px solid
    ${active ? theme.colors.background.brandStrong : theme.colors.stroke.focusRing};
  border-radius: 12px;
  background-color: ${active ? theme.colors.background.brandStrong : theme.colors.background.surface};
  color: ${active ? theme.colors.foreground.onBrand : theme.colors.foreground.brand};
  box-shadow: 0 3px 0
    ${active ? theme.colors.stroke.focusRing : theme.colors.stroke.brandWeak};
  font-size: 13px;
  font-weight: 700;
  line-height: 22px;
  white-space: nowrap;
  cursor: pointer;
  transition:
    background-color 0.15s ease,
    box-shadow 0.15s ease,
    transform 0.15s ease;
  &:hover {
    background-color: ${active ? theme.colors.background.brandStrong : theme.colors.background.brandWeak};
    box-shadow: 0 5px 0
      ${active ? theme.colors.stroke.focusRing : theme.colors.stroke.brandWeak};
    transform: translateY(-2px);
  }
  &:active {
    box-shadow: none;
    transform: translateY(3px);
  }
  &:focus-visible {
    outline: 3px solid ${theme.colors.stroke.focusRing};
    outline-offset: 1px;
  }
  @media (prefers-reduced-motion: reduce) {
    transition: none;
    &:hover,
    &:active {
      transform: none;
    }
  }
`;

const exampleChoiceIconStyle = css`
  font-size: 16px;
  line-height: 20px;
`;

const chatColors = {
  background: '#B9CDD8',
  header: '#DCE7ED',
  sentBubble: '#FEE500',
  text: '#26343D',
  muted: '#4E626F',
} as const;

const shareSceneStyle = css`
  margin: 12px -8px 0;
  overflow: hidden;
  border: 1px solid ${chatColors.background};
  border-radius: 26px;
  background-color: ${chatColors.background};
  color: ${chatColors.text};
`;

const chatHeaderStyle = css`
  display: grid;
  grid-template-columns: 24px minmax(0, 1fr) 24px;
  gap: 12px;
  align-items: center;
  padding: 20px 18px;
  background-color: ${chatColors.header};
  text-align: center;

  strong {
    font-size: 16px;
    font-weight: 700;
    line-height: 24px;
  }
`;

const chatNavigationIconStyle = css`
  font-size: 30px;
  line-height: 24px;
`;

const chatSceneLabelStyle = css`
  width: fit-content;
  margin: 18px auto 0;
  padding: 5px 12px;
  border-radius: 16px;
  background-color: rgb(38 52 61 / 9%);
  color: ${chatColors.muted};
  font-size: 10px;
  line-height: 18px;
`;

const chatBodyStyle = css`
  position: relative;
  display: flex;
  flex-direction: column;
  gap: 12px;
  padding: 22px 14px 28px;
`;

const sentRowStyle = css`
  display: flex;
  align-self: flex-end;
  align-items: flex-start;
  justify-content: flex-end;
  gap: 8px;
  max-width: 92%;
`;

const sentContentStyle = css`
  display: grid;
  justify-items: end;
  gap: 5px;
`;

const avatarStyle = css`
  display: block;
  flex-shrink: 0;
  width: 30px;
  height: 30px;
  border: 2px solid rgb(255 255 255 / 70%);
  border-radius: 10px;
  background-color: ${theme.colors.background.surface};
  object-fit: contain;
`;

const chatNameStyle = css`
  color: ${chatColors.muted};
  font-size: 11px;
  line-height: 18px;
`;

const sentMessageStyle = css`
  position: relative;
  margin: 0;
  padding: 11px 13px;
  border-radius: 12px;
  background-color: ${chatColors.sentBubble};
  font-size: 13px;
  line-height: 22px;
  word-break: keep-all;

  &::after {
    position: absolute;
    top: 10px;
    right: -5px;
    width: 9px;
    height: 9px;
    background-color: inherit;
    clip-path: polygon(0 0, 100% 0, 0 100%);
    content: '';
  }
`;

const receivedRowStyle = css`
  display: flex;
  align-items: flex-start;
  gap: 8px;
  max-width: 92%;
`;

const receivedContentStyle = css`
  display: grid;
  justify-items: start;
  gap: 5px;
`;

const replyMessageStyle = css`
  position: relative;
  margin: 0;
  padding: 11px 13px;
  border-radius: 12px;
  background-color: ${theme.colors.background.surface};
  font-size: 13px;
  line-height: 22px;
  word-break: keep-all;

  &::before {
    position: absolute;
    top: 10px;
    left: -5px;
    width: 9px;
    height: 9px;
    background-color: inherit;
    clip-path: polygon(0 0, 100% 0, 100% 100%);
    content: '';
  }
`;

const followUpMessageStyle = css`
  ${replyMessageStyle};
  align-self: flex-start;
  max-width: calc(92% - 38px);
  margin-left: 38px;

  &::before {
    display: none;
  }
`;

const sharedImageStyle = css`
  display: block;
  align-self: flex-start;
  width: min(248px, calc(100% - 38px));
  height: auto;
  aspect-ratio: 1;
  margin-left: 38px;
  border-radius: 12px;
`;

const chatComposerStyle = css`
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 12px;
  background-color: rgb(255 255 255 / 28%);
`;

const chatAddStyle = css`
  color: ${chatColors.muted};
  font-size: 24px;
  line-height: 30px;
`;

const chatInputStyle = css`
  display: flex;
  flex: 1;
  align-items: center;
  justify-content: space-between;
  min-width: 0;
  padding: 11px 12px;
  border-radius: 10px;
  background-color: ${theme.colors.background.surface};
  color: ${chatColors.muted};
  font-size: 12px;
  line-height: 20px;

  span {
    font-size: 20px;
  }
`;

const finalSectionStyle = css`
  ${sectionStyle};
  align-items: center;
  padding-right: 20px;
  padding-left: 20px;
  padding-bottom: 32px;
  background-color: ${theme.colors.background.brandWeak};
  text-align: center;
`;

const finalImageStyle = css`
  width: min(100%, 336px);
  aspect-ratio: 2;
  margin-bottom: 8px;
  object-fit: cover;
  object-position: center 62.5%;
`;

const trialTargetStyle = css`
  width: 100%;
  margin-top: 12px;
  scroll-margin-top: 20px;
  text-align: left;
`;

const continueLoginStyle = css`
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: center;
  gap: 0 8px;
  margin-top: 4px;
  color: ${theme.colors.foreground.neutralMuted};
  font-size: 13px;
  line-height: 22px;
  word-break: keep-all;
`;
const footerStyle = css`
  display: grid;
  gap: 14px;
  padding: 24px 20px calc(24px + env(safe-area-inset-bottom));
  border-top: 1px solid ${theme.colors.stroke.outline};
  background-color: ${theme.colors.background.surface};
  color: ${theme.colors.foreground.neutralMuted};
  font-size: 11px;
  line-height: 20px;
  text-align: center;
`;
const footerBrandStyle = css`
  display: flex;
  flex-wrap: wrap;
  justify-content: center;
  align-items: center;
  gap: 4px 8px;
  margin: 0;
  strong {
    color: ${theme.colors.foreground.neutral};
    font-size: 15px;
  }
`;
const footerLinksStyle = css`
  display: flex;
  flex-wrap: wrap;
  justify-content: center;
  gap: 8px 12px;
  a {
    color: inherit;
    text-underline-offset: 3px;
    &:focus-visible {
      outline: 2px solid ${theme.colors.stroke.focusRing};
      outline-offset: 4px;
    }
  }
`;
const copyrightStyle = css`
  margin: 0;
`;

const typingStyle = css`
  position: absolute;
  right: 44px;
  bottom: 30px;
  margin: 0;
  padding: 8px 14px;
  border-radius: 12px;
  background-color: ${chatColors.sentBubble};
  letter-spacing: 4px;
  visibility: hidden;
`;

const stickyCtaStyle = css`
  position: fixed;
  z-index: 5;
  bottom: 0;
  left: 50%;
  width: min(100%, 430px);
  transform: translateX(-50%);
  padding: 12px 20px max(12px, env(safe-area-inset-bottom));
  border-top: 1px solid ${theme.colors.stroke.brandWeak};
  background-color: rgb(255 255 255 / 96%);
`;

const scrollHintStyle = css`
  position: fixed;
  z-index: 4;
  bottom: calc(24px + env(safe-area-inset-bottom));
  left: 50%;
  display: grid;
  place-items: center;
  width: 52px;
  height: 52px;
  padding: 0;
  border: none;
  border-radius: 50%;
  background-color: ${theme.colors.background.brandStrong};
  color: ${theme.colors.foreground.onBrand};
  box-shadow:
    0 10px 24px rgb(72 44 151 / 32%),
    0 3px 8px rgb(72 44 151 / 18%);
  transform: translateX(-50%);
  cursor: pointer;
  &:focus-visible {
    outline: 3px solid ${theme.colors.stroke.focusRing};
    outline-offset: 3px;
  }
`;

const scrollArrowStyle = css`
  display: block;
  flex-shrink: 0;
  animation: landing-scroll-hint 1.8s ease-in-out infinite;
  @keyframes landing-scroll-hint {
    0%,
    100% {
      transform: translateY(-3px);
    }
    50% {
      transform: translateY(3px);
    }
  }
  @media (prefers-reduced-motion: reduce) {
    animation: none;
  }
`;

const imageReactionsStyle = css`
  display: flex;
  align-self: flex-start;
  gap: 6px;
  margin: -6px 0 0 38px;
  padding: 0;
  list-style: none;
  li {
    display: flex;
    align-items: center;
    gap: 4px;
    min-height: 28px;
    padding: 3px 9px 3px 6px;
    border: 1px solid rgb(255 255 255 / 55%);
    border-radius: 16px;
    background-color: rgb(255 255 255 / 60%);
    color: ${chatColors.muted};
    font-size: 12px;
    font-weight: 600;
    line-height: 20px;
  }
`;

const reactionEmojiStyle = css`
  font-size: 19px;
  line-height: 22px;
`;
