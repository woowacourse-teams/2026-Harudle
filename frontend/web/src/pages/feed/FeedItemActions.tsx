import { css } from '@emotion/react';
import heartIcon from '../../assets/icons/feed-heart.svg';
import commentIcon from '../../assets/icons/feed-comment.svg';
import sendIcon from '../../assets/icons/feed-send.svg';
import type { Feed } from '../../domain/feeds/feedList';
import { theme } from '../../styles/theme';

const FeedItemActions = ({
  likeCount,
  commentCount,
}: Pick<Feed, 'likeCount' | 'commentCount'>) => {
  return (
    <div css={feedItemActionsStyle}>
      <button
        type="button"
        aria-label={`좋아요 ${likeCount}개`}
        css={reactionButtonStyle}
      >
        <span css={reactionIconStyle}>
          <img src={heartIcon} alt="" />
        </span>
        <span>{likeCount}</span>
      </button>
      <button
        type="button"
        aria-label={`댓글 ${commentCount}개`}
        css={reactionButtonStyle}
      >
        <span css={reactionIconStyle}>
          <img src={commentIcon} alt="" />
        </span>
        <span>{commentCount}</span>
      </button>
      <button type="button" aria-label="피드 공유" css={shareButtonStyle}>
        <span css={reactionIconStyle}>
          <img src={sendIcon} alt="" />
        </span>
      </button>
    </div>
  );
};

export default FeedItemActions;

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

const shareButtonStyle = css`
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
  width: 32px;
  height: 32px;
  padding: 0;
  border: none;
  background: none;
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
