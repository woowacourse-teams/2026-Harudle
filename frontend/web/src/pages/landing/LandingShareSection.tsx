/** @jsxImportSource @emotion/react */
import { css } from '@emotion/react';
import type { JSX, RefObject } from 'react';
import { LANDING_COPY, LANDING_EXAMPLES } from './copy';
import LandingSlackCommunity from './LandingSlackCommunity';

interface LandingShareSectionProps {
  readonly pageRef: RefObject<HTMLElement | null>;
}

import chatMeImage from './assets/chat-me.png';
import chatFriendImage from './assets/chat-friend.png';

const LandingShareSection = ({
  pageRef,
}: LandingShareSectionProps): JSX.Element => (
  <section id="share" css={sectionStyle} aria-labelledby="landing-share-title">
    <div css={innerStyle}>
      <header css={headingStyle} data-reveal="share-heading">
        <h2 id="landing-share-title">{LANDING_COPY.share.title}</h2>
      </header>

      <div css={columnsStyle}>
        <article css={columnStyle} aria-labelledby="landing-chat-title">
          <div css={columnIntroStyle} data-reveal="chat-intro">
            <h3 id="landing-chat-title">
              함께 웃었던 순간을
              <br />
              다시 꺼내요
            </h3>
          </div>
          <div css={chatStageStyle}>
            <figure
              data-reveal="chat"
              css={chatFrameStyle}
              aria-label="공유 장면 예시"
            >
              <figcaption css={chatHeaderStyle}>
                <svg
                  width="20"
                  height="20"
                  viewBox="0 0 20 20"
                  fill="none"
                  aria-hidden="true"
                >
                  <path
                    d="M12.5 4.5 7 10l5.5 5.5"
                    stroke="currentColor"
                    strokeWidth="1.7"
                    strokeLinecap="round"
                    strokeLinejoin="round"
                  />
                </svg>
                <strong>{LANDING_COPY.share.sceneTitle}</strong>
                <svg
                  width="20"
                  height="20"
                  viewBox="0 0 20 20"
                  fill="none"
                  aria-hidden="true"
                >
                  <path
                    d="M4 6h12M4 10h12M4 14h12"
                    stroke="currentColor"
                    strokeWidth="1.7"
                    strokeLinecap="round"
                  />
                </svg>
              </figcaption>
              <div css={chatBodyStyle}>
                <div data-chat-step="1" css={sentRowStyle}>
                  <div css={sentContentStyle}>
                    <span css={chatNameStyle}>
                      {LANDING_COPY.share.senderName}
                    </span>
                    <p css={sentMessageStyle}>
                      {LANDING_COPY.share.sceneMessage}
                    </p>
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
                    <p css={replyMessageStyle}>
                      {LANDING_COPY.share.sceneReply}
                    </p>
                  </div>
                </div>
                <p data-chat-step="2" css={followUpStyle}>
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
                  {LANDING_COPY.share.imageReactions.map((reaction) => (
                    <li
                      key={reaction.emoji}
                      aria-label={`${reaction.label} ${reaction.count}명`}
                    >
                      <span aria-hidden="true">{reaction.emoji}</span>
                      <span aria-hidden="true">{reaction.count}</span>
                    </li>
                  ))}
                </ul>
                <p data-chat-step="4" css={typingStyle} aria-hidden="true">
                  <span />
                  <span />
                  <span />
                </p>
                <div data-chat-step="5" css={sentRowStyle}>
                  <p css={sentMessageStyle}>
                    {LANDING_COPY.share.sceneReaction}
                  </p>
                  <img src={chatMeImage} alt="나의 프로필" css={avatarStyle} />
                </div>
              </div>
              <div css={chatComposerStyle} aria-hidden="true">
                <span css={composerPlusStyle}>+</span>
                <span css={composerFieldStyle}>
                  {LANDING_COPY.share.inputPlaceholder}
                  <span>☺</span>
                </span>
              </div>
            </figure>
          </div>
        </article>

        <article css={columnStyle} aria-labelledby="landing-community-title">
          <div css={columnIntroStyle} data-reveal="community-intro">
            <h3 id="landing-community-title">
              우아한테크코스에서
              <br />
              이렇게 나누고 있어요
            </h3>
          </div>
          <div css={slackStageStyle}>
            <LandingSlackCommunity pageRef={pageRef} />
          </div>
        </article>
      </div>
    </div>
  </section>
);

export default LandingShareSection;

const sectionStyle = css`
  position: relative;
  padding: clamp(84px, 9vw, 140px) 24px;
  overflow: hidden;
  background: #fff;
  color: #1c1530;
  @media (max-width: 600px) {
    padding: 84px 20px;
  }
`;
const innerStyle = css`
  width: min(100%, 1180px);
  margin: 0 auto;
`;
const headingStyle = css`
  margin-bottom: clamp(38px, 5vw, 68px);
  h2 {
    margin: 0;
    font-size: clamp(34px, 4.5vw, 60px);
    font-weight: 800;
    line-height: 1.18;
    letter-spacing: -0.055em;
    white-space: pre-line;
    word-break: keep-all;
  }
  @media (max-width: 720px) {
    h2 {
      font-size: clamp(32px, 8.5vw, 42px);
    }
  }
`;
const columnsStyle = css`
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: clamp(24px, 4vw, 52px);
  align-items: start;
  @media (max-width: 720px) {
    grid-template-columns: 1fr;
    gap: 60px;
  }
`;
const columnStyle = css`
  min-width: 0;
`;
const columnIntroStyle = css`
  min-height: 112px;
  padding: 0 2px 22px;
  h3 {
    margin: 0;
    font-size: clamp(24px, 2.25vw, 31px);
    font-weight: 800;
    line-height: 1.35;
    letter-spacing: -0.045em;
    white-space: pre-line;
    word-break: keep-all;
  }
  @media (max-width: 720px) {
    min-height: 0;
    padding-bottom: 24px;
  }
`;
const chatStageStyle = css`
  display: grid;
  place-items: center;
  padding: clamp(18px, 3vw, 34px);
  border: 1px solid #eee8fb;
  border-radius: 32px;
  background:
    radial-gradient(circle at 15% 12%, #fff 0 6px, transparent 7px),
    radial-gradient(circle at 87% 78%, #e0d4ff 0 5px, transparent 6px), #f7f4ff;
  @media (max-width: 380px) {
    padding: 14px;
    border-radius: 26px;
  }
`;
const chatFrameStyle = css`
  width: min(100%, 430px);
  margin: 0;
  overflow: hidden;
  border: 1px solid #d5cce9;
  border-radius: 24px;
  background: #e3deef;
  box-shadow: 0 22px 48px rgb(62 42 111 / 12%);
  color: #292336;
`;
const chatHeaderStyle = css`
  display: grid;
  grid-template-columns: 20px minmax(0, 1fr) 20px;
  align-items: center;
  gap: 12px;
  padding: 18px 16px;
  background: #f4f1fc;
  text-align: center;
  strong {
    font-size: 14px;
    font-weight: 800;
    line-height: 22px;
  }
`;
const chatBodyStyle = css`
  position: relative;
  display: flex;
  flex-direction: column;
  gap: 12px;
  padding: 22px 14px 28px;
`;
const avatarStyle = css`
  display: block;
  flex: 0 0 32px;
  width: 32px;
  height: 32px;
  border: 2px solid rgb(255 255 255 / 85%);
  border-radius: 11px;
  background: #fff;
  object-fit: contain;
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
const chatNameStyle = css`
  color: #777081;
  font-size: 11px;
  line-height: 18px;
`;
const messageBaseStyle = css`
  position: relative;
  margin: 0;
  padding: 11px 13px;
  border-radius: 13px;
  font-size: 13px;
  line-height: 22px;
  word-break: keep-all;
`;
const sentMessageStyle = css`
  ${messageBaseStyle};
  background: #d8ccff;
  &::after {
    position: absolute;
    top: 10px;
    right: -5px;
    width: 9px;
    height: 9px;
    background: inherit;
    clip-path: polygon(0 0, 100% 0, 0 100%);
    content: '';
  }
`;
const replyMessageStyle = css`
  ${messageBaseStyle};
  background: #fff;
  &::before {
    position: absolute;
    top: 10px;
    left: -5px;
    width: 9px;
    height: 9px;
    background: inherit;
    clip-path: polygon(0 0, 100% 0, 100% 100%);
    content: '';
  }
`;
const followUpStyle = css`
  ${replyMessageStyle};
  align-self: flex-start;
  max-width: calc(92% - 40px);
  margin-left: 40px;
  &::before {
    display: none;
  }
`;
const sharedImageStyle = css`
  display: block;
  align-self: flex-start;
  width: min(248px, calc(100% - 40px));
  height: auto;
  aspect-ratio: 1;
  margin-left: 40px;
  border-radius: 12px;
`;
const imageReactionsStyle = css`
  display: flex;
  align-self: flex-start;
  gap: 6px;
  margin: -6px 0 0 40px;
  padding: 0;
  list-style: none;
  li {
    display: flex;
    align-items: center;
    gap: 4px;
    min-height: 28px;
    padding: 3px 9px 3px 6px;
    border: 1px solid rgb(255 255 255 / 70%);
    border-radius: 999px;
    background: rgb(255 255 255 / 70%);
    color: #746b82;
    font-size: 12px;
    font-weight: 700;
    line-height: 20px;
  }
  li span:first-of-type {
    font-size: 18px;
  }
`;
const typingStyle = css`
  position: absolute;
  right: 44px;
  bottom: 30px;
  display: flex;
  align-items: center;
  gap: 6px;
  margin: 0;
  padding: 12px 14px;
  border-radius: 13px;
  background: #d8ccff;
  visibility: hidden;
  span {
    width: 5px;
    height: 5px;
    border-radius: 50%;
    background: #615578;
    animation: share-typing-dot 0.7s ease-in-out infinite;
    animation-play-state: paused;
  }
  span:nth-of-type(2) {
    animation-delay: 0.12s;
  }
  span:nth-of-type(3) {
    animation-delay: 0.24s;
  }
  @keyframes share-typing-dot {
    0%,
    60%,
    100% {
      transform: translateY(0);
    }
    30% {
      transform: translateY(-4px);
    }
  }
  @media (prefers-reduced-motion: reduce) {
    span {
      animation: none;
    }
  }
`;
const chatComposerStyle = css`
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 12px;
  background: rgb(255 255 255 / 35%);
`;
const composerPlusStyle = css`
  color: #8e859d;
  font-size: 23px;
  line-height: 30px;
`;
const composerFieldStyle = css`
  display: flex;
  flex: 1;
  align-items: center;
  justify-content: space-between;
  min-width: 0;
  padding: 10px 12px;
  border-radius: 11px;
  background: #fff;
  color: #a29aaa;
  font-size: 12px;
  line-height: 20px;
  span {
    color: #a29aaa;
    font-size: 20px;
  }
`;
const slackStageStyle = css`
  padding: 8px;
  overflow: hidden;
  border: 1px solid #e8e2f4;
  border-radius: 32px;
  background: #f7f4ff;
  > figure {
    margin: 0;
    border-radius: 24px;
  }
  @media (max-width: 380px) {
    padding: 6px;
    border-radius: 26px;
  }
`;
