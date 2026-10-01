import {
  useEffect,
  useRef,
  useState,
  type CSSProperties,
  type JSX,
  type RefObject,
} from 'react';
import { css } from '@emotion/react';
import chatMeImage from './assets/chat-me.png';
import LandingComicPreview from './LandingComicPreview';
import { LANDING_COPY, LANDING_SLACK_STORY } from './copy';

interface LandingSlackCommunityProps {
  readonly pageRef: RefObject<HTMLElement | null>;
}

const LandingSlackCommunity = ({
  pageRef,
}: LandingSlackCommunityProps): JSX.Element => {
  const frameRef = useRef<HTMLElement>(null);
  const [motionEnabled, setMotionEnabled] = useState(
    (): boolean =>
      typeof IntersectionObserver !== 'undefined' &&
      !window.matchMedia?.('(prefers-reduced-motion: reduce)').matches,
  );
  const [active, setActive] = useState(!motionEnabled);
  const [progress, setProgress] = useState(motionEnabled ? 0 : 1);

  useEffect(() => {
    const element = frameRef.current;
    const media = window.matchMedia?.('(prefers-reduced-motion: reduce)');
    if (
      !element ||
      typeof IntersectionObserver === 'undefined' ||
      media?.matches
    )
      return;
    let animationFrame: number | undefined;
    const observer = new IntersectionObserver(
      (entries): void => {
        if (!entries.some((entry): boolean => entry.isIntersecting)) return;
        observer.disconnect();
        setActive(true);
        const startedAt = performance.now();
        const animate = (now: number): void => {
          const elapsed = Math.max(
            0,
            Math.min((now - startedAt - 1250) / 600, 1),
          );
          setProgress(1 - Math.pow(1 - elapsed, 3));
          if (elapsed < 1)
            animationFrame = window.requestAnimationFrame(animate);
        };
        animationFrame = window.requestAnimationFrame(animate);
      },
      {
        root: pageRef.current,
        threshold: 0.3,
        rootMargin: '0px 0px -80px 0px',
      },
    );
    observer.observe(element);
    const handleMotionChange = (): void => {
      if (!media?.matches) return;
      observer.disconnect();
      if (animationFrame !== undefined)
        window.cancelAnimationFrame(animationFrame);
      setMotionEnabled(false);
      setActive(true);
      setProgress(1);
    };
    media?.addEventListener('change', handleMotionChange);
    return (): void => {
      observer.disconnect();
      if (animationFrame !== undefined)
        window.cancelAnimationFrame(animationFrame);
      media?.removeEventListener('change', handleMotionChange);
    };
  }, [pageRef]);

  return (
    <figure
      ref={frameRef}
      data-reveal="slack"
      data-revealed={active ? 'true' : undefined}
      data-slack-active={active}
      data-slack-motion={motionEnabled ? 'enabled' : 'disabled'}
      css={frameStyle}
      aria-label={LANDING_COPY.share.slackLabel}
    >
      <figcaption css={headerStyle}>
        <strong>
          <span aria-hidden="true">#</span>{' '}
          {LANDING_COPY.share.slackChannelName}
        </strong>
        <span css={platformStyle}>Slack</span>
      </figcaption>
      <div data-slack-phase css={postStyle}>
        <div css={senderStyle}>
          <img src={chatMeImage} alt="" css={avatarStyle} />
          <strong>{LANDING_SLACK_STORY.sender}</strong>
          <span css={timeStyle}>{LANDING_SLACK_STORY.time}</span>
        </div>
        <div css={messageStyle}>
          <p css={messageTitleStyle}>{LANDING_SLACK_STORY.title}</p>
          <div data-slack-main>
            <LandingComicPreview example={LANDING_SLACK_STORY} />
          </div>
          <ul css={reactionsStyle} aria-label="실제 슬랙 반응">
            {LANDING_SLACK_STORY.reactions.map(
              (reaction, index): JSX.Element => (
                <li
                  key={reaction.label}
                  data-slack-phase
                  style={
                    {
                      '--slack-delay': `${1.25 + index * 0.18}s`,
                    } as CSSProperties
                  }
                  aria-label={`${reaction.label} 반응 ${reaction.count}개`}
                >
                  <span
                    aria-hidden="true"
                    css={
                      reaction.kind === 'emoji' ? emojiStyle : reactionTextStyle
                    }
                  >
                    {reaction.value}
                  </span>
                  <span aria-hidden="true">
                    {Math.round(reaction.count * progress)}
                  </span>
                </li>
              ),
            )}
          </ul>
          <div css={threadStyle}>
            <div css={repliesStyle}>
              <p data-slack-typing aria-hidden="true" css={typingStyle}>
                입력 중<span>…</span>
              </p>
              {LANDING_SLACK_STORY.replies.map((reply, index): JSX.Element => (
                <article
                  key={reply.message}
                  aria-label={`${reply.sender}의 댓글`}
                  data-slack-reply
                  data-slack-phase
                  style={
                    {
                      '--slack-delay': `${3.05 + index * 0.22}s`,
                    } as CSSProperties
                  }
                  css={replyStyle}
                >
                  <img
                    src={reply.profileImageUrl}
                    alt={`${reply.sender}의 프로필`}
                    css={replyAvatarStyle}
                  />
                  <div>
                    <div css={replySenderStyle}>
                      <strong>{reply.sender}</strong>
                      <span>{reply.time}</span>
                    </div>
                    <p css={replyTextStyle}>{reply.message}</p>
                  </div>
                </article>
              ))}
            </div>
            <p
              data-slack-phase
              style={{ '--slack-delay': '3.4s' } as CSSProperties}
              css={threadCountStyle}
            >
              <span aria-hidden="true">💬 </span>
              댓글{' '}
              {LANDING_SLACK_STORY.replyCount -
                LANDING_SLACK_STORY.replies.length}
              개 더 보기
            </p>
          </div>
        </div>
      </div>
      <ul css={relatedPostsStyle} aria-label="다른 네컷만화 공유 글">
        {LANDING_SLACK_STORY.relatedPosts.map((post, index): JSX.Element => (
          <li
            key={post.title}
            data-slack-phase
            style={
              { '--slack-delay': `${3.7 + index * 0.25}s` } as CSSProperties
            }
          >
            <img
              src={post.imageUrl}
              alt={`${post.title} 네컷만화 썸네일`}
              loading="lazy"
              decoding="async"
              css={relatedComicStyle}
            />
            <div>
              <div css={relatedSenderStyle}>
                {post.profileImageUrl && (
                  <img
                    src={post.profileImageUrl}
                    alt={`${post.sender}의 프로필`}
                    css={avatarStyle}
                  />
                )}
                <strong>{post.sender}</strong>
              </div>
              <p>{post.title}</p>
              <span css={timeStyle}>{post.time}</span>
              <span css={relatedReplyCountStyle}>
                {post.replyCount}개의 댓글
              </span>
              <ul css={relatedReactionsStyle} aria-label={`${post.title} 반응`}>
                {post.reactions.map((reaction, reactionIndex): JSX.Element => (
                  <li
                    key={reactionIndex}
                    aria-label={
                      reaction.count === null
                        ? reaction.label
                        : `${reaction.label} 반응 ${reaction.count}개`
                    }
                  >
                    <span aria-hidden="true">{reaction.emoji}</span>
                    {reaction.count !== null && (
                      <span aria-hidden="true">{reaction.count}</span>
                    )}
                  </li>
                ))}
              </ul>
            </div>
          </li>
        ))}
      </ul>
    </figure>
  );
};

export default LandingSlackCommunity;

const frameStyle = css`
  margin: 8px -8px 0;
  overflow: hidden;
  border: 1px solid #36373c;
  border-radius: 20px;
  background-color: #1a1d21;
  color: #f8f8f8;
  &[data-slack-motion='disabled'] [data-comic-mask] {
    opacity: 0 !important;
    transform: none !important;
    animation: none !important;
  }
  &[data-slack-motion='enabled'] [data-slack-phase] {
    opacity: 0;
  }
  &[data-slack-motion='enabled'][data-slack-active='true'] [data-slack-phase] {
    animation: slack-message-in 0.35s ease-out both;
    animation-delay: var(--slack-delay, 0.15s);
  }
  &[data-slack-motion='enabled'][data-slack-active='true'] [data-slack-typing] {
    animation: slack-typing 0.75s 2.25s both;
  }
  @keyframes slack-message-in {
    from {
      opacity: 0;
      transform: translateY(8px);
    }
    to {
      opacity: 1;
      transform: none;
    }
  }
  @keyframes slack-typing {
    0%,
    85% {
      opacity: 1;
    }
    100% {
      opacity: 0;
    }
  }
  @media (prefers-reduced-motion: reduce) {
    [data-slack-phase] {
      opacity: 1 !important;
      transform: none !important;
      animation: none !important;
    }
  }
`;
const headerStyle = css`
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  padding: 16px;
  border-bottom: 1px solid #36373c;
  strong {
    font-size: 15px;
    line-height: 22px;
  }
  strong span {
    margin-right: 4px;
    color: #b7b8bc;
  }
`;
const platformStyle = css`
  color: #b7b8bc;
  font-size: 11px;
`;
const postStyle = css`
  padding: 20px 14px;
`;
const senderStyle = css`
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 7px;
  font-size: 13px;
  line-height: 20px;
`;
const avatarStyle = css`
  width: 30px;
  height: 30px;
  border-radius: 7px;
  background-color: #fff;
  object-fit: contain;
`;
const timeStyle = css`
  color: #b7b8bc;
  font-size: 11px;
  line-height: 18px;
`;
const messageStyle = css`
  display: grid;
  gap: 12px;
  margin: 8px 0 0 37px;
  @media (max-width: 350px) {
    margin-left: 0;
  }
`;
const messageTitleStyle = css`
  margin: 0;
  font-size: 13px;
  line-height: 20px;
`;
const reactionsStyle = css`
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  margin: 0;
  padding: 0;
  list-style: none;
  li {
    display: flex;
    align-items: center;
    gap: 6px;
    min-height: 30px;
    padding: 4px 9px;
    border: 1px solid #46484f;
    border-radius: 16px;
    background-color: #272a2f;
    font-size: 13px;
    line-height: 20px;
    font-variant-numeric: tabular-nums;
  }
`;
const emojiStyle = css`
  font-family:
    'Apple Color Emoji', 'Segoe UI Emoji', 'Noto Color Emoji', sans-serif;
  font-size: 20px;
  line-height: 22px;
`;
const reactionTextStyle = css`
  padding: 0 3px;
  border-radius: 3px;
  background-color: #f8f8f8;
  color: #1a1d21;
  font-size: 11px;
  font-weight: 800;
`;
const threadStyle = css`
  display: grid;
  gap: 12px;
  margin-top: 2px;
`;
const threadCountStyle = css`
  margin: 0;
  color: #59c5e4;
  font-size: 12px;
  font-weight: 600;
  line-height: 20px;
`;
const repliesStyle = css`
  position: relative;
  display: grid;
  gap: 10px;
`;
const typingStyle = css`
  position: absolute;
  inset: 0 auto auto 0;
  margin: 0;
  color: #b7b8bc;
  font-size: 12px;
  opacity: 0;
  @media (prefers-reduced-motion: reduce) {
    display: none;
  }
`;
const replyStyle = css`
  display: grid;
  grid-template-columns: 28px minmax(0, 1fr);
  gap: 10px;
  padding: 10px 12px;
  border-radius: 8px;
  background-color: rgba(255, 255, 255, 0.04);
  word-break: keep-all;
`;
const replyAvatarStyle = css`
  width: 28px;
  height: 28px;
  border-radius: 6px;
  background-color: #fff;
`;
const replySenderStyle = css`
  display: flex;
  flex-wrap: wrap;
  align-items: baseline;
  gap: 2px 6px;
  strong {
    color: #fff;
    font-size: 14px;
    line-height: 20px;
  }
  span {
    color: #9a9a9a;
    font-size: 12px;
    line-height: 20px;
  }
`;
const replyTextStyle = css`
  margin: 2px 0 0;
  color: #e8e8e8;
  font-size: 15px;
  font-weight: 400;
  line-height: 22px;
`;
const relatedPostsStyle = css`
  margin: 0;
  padding: 0;
  list-style: none;
  > li {
    display: grid;
    grid-template-columns: 100px minmax(0, 1fr);
    align-items: center;
    gap: 12px;
    padding: 16px 14px;
    border-top: 1px solid #36373c;
  }
  strong {
    display: block;
    font-size: 11px;
    line-height: 18px;
  }
  p {
    margin: 0 0 3px;
    font-size: 12px;
    font-weight: 600;
    line-height: 20px;
    word-break: keep-all;
  }
`;
const relatedComicStyle = css`
  display: block;
  width: 100px;
  height: 100px;
  border-radius: 7px;
  object-fit: contain;
`;
const relatedReactionsStyle = css`
  display: flex;
  flex-wrap: wrap;
  gap: 4px;
  margin: 8px 0 0;
  padding: 0;
  list-style: none;
  li {
    display: flex;
    align-items: center;
    gap: 3px;
    padding: 3px 6px;
    border: 1px solid #46484f;
    border-radius: 12px;
    background-color: #272a2f;
    font-size: 11px;
    line-height: 16px;
  }
`;
const relatedSenderStyle = css`
  display: flex;
  align-items: center;
  gap: 7px;
  margin-bottom: 5px;
`;
const relatedReplyCountStyle = css`
  margin-left: 10px;
  color: #59c5e4;
  font-size: 11px;
  line-height: 18px;
`;
