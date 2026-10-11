import { css } from '@emotion/react';
import categoryChevronIcon from '../../assets/icons/feed-category-chevron.svg';
import profileFallbackImage from '../../assets/images/profile-fallback.svg';
import type { Feed } from '../../domain/feeds/feedList';
import DiaryImage from '../../shared/DiaryImage';
import { theme } from '../../styles/theme';
import FeedItemActions from './FeedItemActions';

interface FeedItemProps {
  readonly feed: Feed;
}

const FeedItem = ({ feed }: FeedItemProps) => {
  const { author, category, publishedAt, imageUrl } = feed;

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
          <span css={categoryStyle}>
            <img src={categoryChevronIcon} />
            <span>{category.name}</span>
          </span>
          <time css={publishedAtStyle} dateTime={publishedAt}>
            {formatPublishedAt(publishedAt)}
          </time>
        </div>
      </header>
      <DiaryImage
        src={imageUrl}
        alt={`${author.nickname}의 만화 피드`}
        imageRole="feed"
        css={comicImageStyle}
      />
      <FeedItemActions />
    </article>
  );
};

export default FeedItem;

const formatPublishedAt = (publishedAt: string): string => {
  const elapsed = Date.now() - new Date(publishedAt).getTime();
  if (!Number.isFinite(elapsed)) return '';
  const minutes = Math.max(0, Math.floor(elapsed / 60_000));
  if (minutes < 1) return '방금 전';
  if (minutes < 60) return `${minutes}분 전`;
  const hours = Math.floor(minutes / 60);
  if (hours < 24) return `${hours}시간 전`;
  return `${Math.floor(hours / 24)}일 전`;
};

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
  font-size: 16px;
  font-weight: 600;
  line-height: 22px;
`;

const publishedAtStyle = css`
  flex-shrink: 0;
  color: ${theme.colors.foreground.neutralMuted};
  font-size: 12px;
  font-weight: 400;
  line-height: 18px;
`;

const comicImageStyle = css`
  display: block;
  width: 100%;
  aspect-ratio: 1;
  object-fit: contain;
`;
