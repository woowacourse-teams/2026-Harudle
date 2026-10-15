import { useEffect, useState } from 'react';
import { css } from '@emotion/react';
import { Link } from 'react-router';
import { getFeedList, type Feed } from '../../domain/feeds/feedList';
import type { ApiRequest } from '../../shared/api';
import DiaryImage from '../../shared/DiaryImage';
import { theme } from '../../styles/theme';
import { LANDING_COPY } from './copy';
import heartIcon from '../../assets/icons/feed-heart.svg';
import commentIcon from '../../assets/icons/feed-comment.svg';

const LandingFeedPreview = () => {
  const [request, setRequest] = useState<ApiRequest<readonly Feed[]>>({
    status: 'loading',
  });
  const [attempt, setAttempt] = useState(0);

  useEffect((): (() => void) => {
    const controller = new AbortController();
    const loadPreview = async (): Promise<void> => {
      try {
        const data = await getFeedList({
          sort: 'POPULAR',
          size: 2,
          signal: controller.signal,
        });
        if (controller.signal.aborted) return;
        setRequest({ status: 'success', data: data.items.slice(0, 2) });
      } catch (error: unknown) {
        if (controller.signal.aborted) return;
        setRequest({
          status: 'error',
          error:
            error instanceof Error ? error : new Error(LANDING_COPY.feed.error),
        });
      }
    };
    void loadPreview();
    return (): void => controller.abort();
  }, [attempt]);

  const handleRetry = (): void => {
    setRequest({ status: 'loading' });
    setAttempt((previous): number => previous + 1);
  };

  if (request.status === 'error') {
    return (
      <div css={feedbackStyle}>
        <p role="status">{LANDING_COPY.feed.error}</p>
        <button type="button" css={retryStyle} onClick={handleRetry}>
          {LANDING_COPY.feed.retry}
        </button>
      </div>
    );
  }

  if (request.status !== 'success') {
    return (
      <div css={feedbackStyle} role="status">
        {LANDING_COPY.feed.loading}
      </div>
    );
  }

  if (request.data.length === 0) {
    return (
      <p css={feedbackStyle} role="status">
        {LANDING_COPY.feed.empty}
      </p>
    );
  }

  return (
    <ul css={previewListStyle} aria-label="공개 피드 미리보기">
      {request.data.map((feed) => (
        <li key={feed.id}>
          <Link
            to={`/feeds/${encodeURIComponent(feed.id)}`}
            css={previewCardStyle}
            aria-label={`${feed.category.name} 네컷만화 보기`}
          >
            <div css={previewImageFrameStyle}>
              <DiaryImage
                src={feed.imageUrl}
                alt={`${feed.category.name} 네컷만화`}
                imageRole="thumbnail"
                css={previewImageStyle}
              />
            </div>
            <div css={previewInfoStyle}>
              <span css={categoryStyle}>{feed.category.name}</span>
              <div css={reactionsStyle}>
                <span css={reactionStyle}>
                  <img src={heartIcon} alt="좋아요" />
                  {feed.likeCount.toLocaleString('ko-KR')}
                </span>
                <span css={reactionStyle}>
                  <img src={commentIcon} alt="댓글" />
                  {feed.commentCount.toLocaleString('ko-KR')}
                </span>
              </div>
            </div>
          </Link>
        </li>
      ))}
    </ul>
  );
};

export default LandingFeedPreview;

const previewListStyle = css`
  display: grid;
  grid-template-columns: minmax(0, 1fr);
  gap: 20px;
  margin: 8px 0;
`;

const previewCardStyle = css`
  display: block;
  overflow: hidden;
  padding: 8px;
  border: 1px solid ${theme.colors.stroke.outline};
  border-radius: 18px;
  background-color: ${theme.colors.background.surface};
  box-shadow: 0 4px 16px rgb(17 17 24 / 4%);
  color: inherit;
  text-decoration: none;
  transition:
    box-shadow 160ms ease,
    transform 160ms ease;

  &:hover {
    box-shadow: 0 8px 20px rgb(17 17 24 / 8%);
    transform: translateY(-2px);
  }

  &:focus-visible {
    outline: 3px solid ${theme.colors.stroke.focusRing};
    outline-offset: 3px;
  }

  @media (prefers-reduced-motion: reduce) {
    transition: none;
    &:hover {
      transform: none;
    }
  }
`;

const previewImageFrameStyle = css`
  overflow: hidden;
  border-radius: 12px;
  background-color: ${theme.colors.background.surface};
`;

const previewImageStyle = css`
  display: block;
  width: 100%;
  aspect-ratio: 1;
  border-radius: 12px;
  object-fit: contain;
  background-color: ${theme.colors.background.surface};
`;

const previewInfoStyle = css`
  display: flex;
  align-items: center;
  justify-content: space-between;
  flex-wrap: wrap;
  gap: 10px;
  padding: 14px 6px 6px;
`;

const categoryStyle = css`
  overflow: hidden;
  width: fit-content;
  max-width: 100%;
  padding: 3px 8px;
  border-radius: 12px;
  background-color: ${theme.colors.background.neutralSubtle};
  color: ${theme.colors.foreground.neutralMuted};
  font-size: 11px;
  line-height: 16px;
  text-overflow: ellipsis;
  white-space: nowrap;
`;

const reactionsStyle = css`
  display: flex;
  flex-wrap: wrap;
  gap: 6px 12px;
  color: ${theme.colors.foreground.neutralMuted};
  font-size: 12px;
  line-height: 18px;
`;

const reactionStyle = css`
  display: flex;
  align-items: center;
  gap: 4px;
  img {
    width: 15px;
    height: 15px;
  }
`;

const feedbackStyle = css`
  display: grid;
  justify-items: center;
  align-content: center;
  gap: 12px;
  min-height: 160px;
  padding: 24px 16px;
  border-radius: 16px;
  background-color: ${theme.colors.background.neutralSubtle};
  color: ${theme.colors.foreground.neutralMuted};
  font-size: 13px;
  line-height: 22px;
  text-align: center;
  word-break: keep-all;
`;

const retryStyle = css`
  min-height: 44px;
  padding: 8px 16px;
  border: 1px solid ${theme.colors.stroke.brandWeak};
  border-radius: 10px;
  background-color: ${theme.colors.background.surface};
  color: ${theme.colors.foreground.brand};
  font-size: 13px;
  font-weight: 600;
  cursor: pointer;

  &:focus-visible {
    outline: 3px solid ${theme.colors.stroke.focusRing};
    outline-offset: 3px;
  }
`;
