import { css } from '@emotion/react';
import commentIcon from '../../assets/icons/feed-comment.svg';
import type { Feed } from '../../domain/feeds/feedList';
import type { FeedLikeResponse } from '../../domain/feeds/feedLike';
import FeedLikeButton from './FeedLikeButton';
import FeedShareButton from './FeedShareButton';
import { theme } from '../../styles/theme';

interface FeedActionsProps extends Pick<
  Feed,
  'likeCount' | 'likedByMe' | 'commentCount' | 'shareUrl'
> {
  readonly feedId: Feed['id'];
  readonly onCommentClick: () => void;
  readonly onLikeChange: (feedId: Feed['id'], like: FeedLikeResponse) => void;
}

const FeedActions = ({
  likeCount,
  commentCount,
  feedId,
  likedByMe,
  onLikeChange,
  shareUrl,
  onCommentClick,
}: FeedActionsProps) => {
  return (
    <div css={feedItemActionsStyle}>
      <FeedLikeButton
        feedId={feedId}
        likeCount={likeCount}
        likedByMe={likedByMe}
        onLikeChange={onLikeChange}
      />
      <button type="button" css={reactionButtonStyle} onClick={onCommentClick}>
        <span css={reactionIconStyle}>
          <img src={commentIcon} alt="" />
        </span>
        <span>{commentCount}</span>
      </button>
      <FeedShareButton shareUrl={shareUrl} />
    </div>
  );
};

export default FeedActions;

const feedItemActionsStyle = css`
  display: flex;
  align-items: center;
  gap: 22px;
  padding-top: 12px;
`;

const reactionButtonStyle = css`
  display: flex;
  align-items: center;
  gap: 7px;
  flex-shrink: 0;
  height: 32px;
  padding: 0;
  border: none;
  background: none;
  color: ${theme.colors.foreground.neutralMuted};
  font-size: 12px;
  font-weight: 400;
  line-height: 20px;
  cursor: pointer;
`;

const reactionIconStyle = css`
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
  width: 23px;
  height: 23px;
  overflow: hidden;

  img {
    display: block;
    flex-shrink: 0;
  }
`;
