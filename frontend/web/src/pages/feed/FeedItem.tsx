import { formatPublishedAt } from '../../shared/feed/formatPublishedAt';
import { css } from '@emotion/react';
import categoryChevronIcon from '../../assets/icons/feed-category-chevron.svg';
import profileFallbackImage from '../../assets/images/profile-fallback.svg';
import type { Feed } from '../../domain/feeds/feedList';
import type { FeedLikeResponse } from '../../domain/feeds/feedLike';
import DiaryImage from '../../shared/DiaryImage';
import { theme } from '../../styles/theme';
import FeedActions from '../../shared/feed/FeedActions';
import { useNavigate } from 'react-router';
import OwnFeedOptionsButton from '../../shared/feed/OwnFeedOptionsButton';
import OtherFeedOptionsButton from '../../shared/content/OtherFeedOptionsButton';

interface FeedItemProps {
  readonly feed: Feed;
  readonly onCategoryClick: (
    categoryId: Feed['category']['id'],
    categoryName: string,
  ) => void;
  readonly onLikeChange: (feedId: Feed['id'], like: FeedLikeResponse) => void;
  readonly onDelete: (feedId: Feed['id']) => void;
}

const FeedItem = ({
  feed,
  onCategoryClick,
  onLikeChange,
  onDelete,
}: FeedItemProps) => {
  const navigate = useNavigate();
  const { author, category, publishedAt, imageUrl, isMine } = feed;

  return (
    <article css={feedItemStyle}>
      <header css={authorStyle}>
        <div css={avatarStyle}>
          <img
            src={author.profileImageUrl || profileFallbackImage}
            css={profileImageStyle}
            onError={(event) => {
              const image = event.currentTarget;
              if (image.getAttribute('src') !== profileFallbackImage) {
                image.src = profileFallbackImage;
              }
            }}
          />
        </div>
        <div css={authorInformationStyle}>
          <span css={authorNameStyle}>{author.nickname}</span>
          <button
            type="button"
            css={categoryStyle}
            onClick={() => onCategoryClick(category.id, category.name)}
          >
            <img src={categoryChevronIcon} alt="" />
            <span>{category.name}</span>
          </button>
          <time css={publishedAtStyle} dateTime={publishedAt}>
            {formatPublishedAt(publishedAt)}
          </time>
        </div>
        {isMine ? (
          <OwnFeedOptionsButton feedId={feed.id} onDelete={onDelete} />
        ) : (
          <OtherFeedOptionsButton
            feedId={feed.id}
            authorId={author.id}
            authorNickname={author.nickname}
          />
        )}
      </header>
      <div onContextMenu={(event) => event.preventDefault()}>
        <DiaryImage
          src={imageUrl}
          alt={`${author.nickname}의 만화 피드`}
          imageRole="feed"
          css={comicImageStyle}
        />
      </div>
      <FeedActions
        onCommentClick={() => {
          void navigate(`/feeds/${feed.id}`, {
            state: { fromFeed: true },
          });
        }}
        shareUrl={feed.shareUrl}
        feedId={feed.id}
        commentCount={feed.commentCount}
        likeCount={feed.likeCount}
        likedByMe={feed.likedByMe}
        onLikeChange={onLikeChange}
      />
    </article>
  );
};

export default FeedItem;

const feedItemStyle = css`
  display: flex;
  flex-direction: column;
  flex-shrink: 0;
  width: 100%;
  padding: 12px 0;
  background-color: ${theme.colors.background.surface};
`;

const authorStyle = css`
  display: flex;
  align-items: center;
  gap: 10px;
  height: 36px;
  margin-bottom: 14px;
`;

const avatarStyle = css`
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
  width: 36px;
  height: 36px;
  border-radius: 50%;
  overflow: hidden;
`;

const profileImageStyle = css`
  width: 100%;
  height: 100%;
  object-fit: cover;
`;

const authorInformationStyle = css`
  display: flex;
  align-items: center;
  gap: 6px;
  flex: 1;
  min-width: 0;
  overflow: hidden;
  color: ${theme.colors.foreground.neutral};
  white-space: nowrap;
`;

const authorNameStyle = css`
  overflow: hidden;
  text-overflow: ellipsis;
  font-size: 16px;
  font-weight: 700;
  line-height: 21px;
`;

const categoryStyle = css`
  display: flex;
  align-items: center;
  gap: 4px;
  flex-shrink: 0;
  height: 32px;
  padding: 0;
  border: none;
  background: none;
  color: inherit;
  font-family: inherit;
  font-size: 16px;
  font-weight: 600;
  line-height: 22px;
  cursor: pointer;

  &:hover span {
    text-decoration: underline;
    text-decoration-color: ${theme.colors.foreground.neutral};
    text-underline-offset: 3px;
  }
`;

const publishedAtStyle = css`
  flex-shrink: 0;
  color: ${theme.colors.foreground.neutralMuted};
  font-size: 12px;
  font-weight: 400;
  line-height: 18px;
`;

const comicImageStyle = css`
  -webkit-touch-callout: none;
  display: block;
  width: 100%;
  aspect-ratio: 1;
  object-fit: contain;
`;
