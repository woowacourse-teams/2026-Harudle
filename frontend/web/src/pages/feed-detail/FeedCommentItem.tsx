import { css } from '@emotion/react';
import FeedCommentOptionsButton from './FeedCommentOptionsButton';
import ReportOptionsButton from '../../shared/report/ReportOptionsButton';
import { theme } from '../../styles/theme';
import type { FeedComment } from '../../domain/feeds/feedComments';
import profileFallbackImage from '../../assets/images/profile-fallback.svg';
import { formatPublishedAt } from '../../shared/feed/formatPublishedAt';

interface FeedCommentItemProps {
  readonly comment: FeedComment;
  readonly onDelete: (commentId: FeedComment['id']) => void;
}

const FeedCommentItem = ({ comment, onDelete }: FeedCommentItemProps) => {
  return (
    <li css={commentStyle}>
      <div css={avatarStyle}>
        <img
          src={comment.author.profileImageUrl || profileFallbackImage}
          alt="프로필 이미지"
          css={profileImageStyle}
          onError={(event) => {
            if (
              event.currentTarget.getAttribute('src') !== profileFallbackImage
            )
              event.currentTarget.src = profileFallbackImage;
          }}
        />
      </div>
      <div css={contentStyle}>
        <div css={informationStyle}>
          <span css={authorNameStyle}>{comment.author.nickname}</span>
          <span css={publishedAtStyle}>
            {formatPublishedAt(comment.createdAt)}
          </span>
        </div>
        <p css={textStyle}>{comment.content}</p>
      </div>
      {comment.isMine ? (
        <FeedCommentOptionsButton commentId={comment.id} onDelete={onDelete} />
      ) : (
        <ReportOptionsButton
          target={{ targetType: 'COMMENT', targetId: comment.id }}
          authorId={comment.author.id}
        />
      )}
    </li>
  );
};

export default FeedCommentItem;

const commentStyle = css`
  display: flex;
  align-items: flex-start;
  gap: 10px;
  padding: 16px 0;
`;

const avatarStyle = css`
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
  width: 32px;
  height: 32px;
  border-radius: 50%;
  background-color: ${theme.colors.background.brandWeak};
`;

const contentStyle = css`
  display: flex;
  flex-direction: column;
  flex: 1;
  gap: 3px;
  min-width: 0;
`;

const informationStyle = css`
  display: flex;
  align-items: center;
  gap: 6px;
`;

const authorNameStyle = css`
  font-size: 13px;
  font-weight: 700;
  overflow-wrap: anywhere;
`;

const publishedAtStyle = css`
  flex-shrink: 0;
  color: ${theme.colors.foreground.neutralMuted};
  font-size: 11px;
`;

const textStyle = css`
  font-size: 14px;
  white-space: pre-wrap;
  overflow-wrap: anywhere;
`;

const profileImageStyle = css`
  width: 100%;
  height: 100%;
  object-fit: cover;
  border-radius: 50%;
`;
