import { css } from '@emotion/react';
import categoryChevronIcon from '../../assets/icons/feed-category-chevron.svg';
import FeedActions from '../../shared/feed/FeedActions';
import OwnFeedOptionsButton from '../../shared/feed/OwnFeedOptionsButton';
import OtherFeedOptionsButton from '../../shared/content/OtherFeedOptionsButton';
import profileFallbackImage from '../../assets/images/profile-fallback.svg';
import type { Feed } from '../../domain/feeds/feedList';
import { useNavigate } from 'react-router';
import backIcon from '../../assets/icons/back.svg';
import harudleLogo from '../../assets/images/harudle-logo.webp';
import LoadingSpinner from '../../shared/LoadingSpinner';
import FeedDetailError from './FeedDetailError';
import FeedComments from './FeedComments';
import FeedCommentInput from './FeedCommentInput';
import useFeedComments from './useFeedComments';
import useFeedDetail from './useFeedDetail';
import { formatPublishedAt } from '../../shared/feed/formatPublishedAt';
import DiaryImage from '../../shared/DiaryImage';
import { theme } from '../../styles/theme';
import useFeedDetailBack from './useFeedDetailBack';

interface FeedDetailContentProps {
  readonly feedId: Feed['id'];
}

const FeedDetailContent = ({ feedId }: FeedDetailContentProps) => {
  const navigate = useNavigate();
  const handleBack = useFeedDetailBack();
  const {
    request: feedDetailRequest,
    updateLike,
    increaseCommentCount,
    decreaseCommentCount,
  } = useFeedDetail({ feedId });
  const comments = useFeedComments({ feedId });

  if (
    feedDetailRequest.status === 'idle' ||
    feedDetailRequest.status === 'loading'
  ) {
    return <LoadingSpinner />;
  }

  if (feedDetailRequest.status === 'error') {
    return <FeedDetailError errorMessage={feedDetailRequest.error.message} />;
  }

  const feed = feedDetailRequest.data;

  return (
    <div css={pageStyle}>
      <header css={pageHeaderStyle}>
        <button
          type="button"
          css={logoButtonStyle}
          onClick={() => {
            void navigate('/');
          }}
        >
          <img css={logoStyle} src={harudleLogo} alt="하루들" />
        </button>
        <button type="button" css={backButtonStyle} onClick={handleBack}>
          <img src={backIcon} alt="뒤로가기 아이콘" />
        </button>
      </header>

      <main css={contentStyle}>
        <article css={feedStyle}>
          <header css={authorStyle}>
            <div css={avatarStyle}>
              <img
                src={feed.author.profileImageUrl || profileFallbackImage}
                alt="프로필 이미지"
                css={profileImageStyle}
                onError={(event) => {
                  if (
                    event.currentTarget.getAttribute('src') !==
                    profileFallbackImage
                  ) {
                    event.currentTarget.src = profileFallbackImage;
                  }
                }}
              />
            </div>
            <div css={authorInformationStyle}>
              <span css={authorNameStyle}>{feed.author.nickname}</span>
              <span css={categoryStyle}>
                <img src={categoryChevronIcon} alt="" />
                {feed.category.name}
              </span>
              <span css={publishedAtStyle}>
                {formatPublishedAt(feed.publishedAt)}
              </span>
            </div>
            {feed.isMine ? (
              <OwnFeedOptionsButton
                feedId={feed.id}
                onDelete={() => {
                  void navigate('/');
                }}
              />
            ) : (
              <OtherFeedOptionsButton
                feedId={feed.id}
                authorId={feed.author.id}
                authorNickname={feed.author.nickname}
              />
            )}
          </header>

          <div onContextMenu={(event) => event.preventDefault()}>
            <DiaryImage
              src={feed.imageUrl}
              alt={`${feed.author.nickname}의 네컷만화`}
              imageRole="feed"
              css={comicImageStyle}
            />
          </div>
          <FeedActions
            feedId={feed.id}
            likeCount={feed.likeCount}
            likedByMe={feed.likedByMe}
            commentCount={feed.commentCount}
            shareUrl={feed.shareUrl}
            onLikeChange={updateLike}
          />
        </article>
        <FeedComments
          request={comments.request}
          loadMoreRequest={comments.loadMoreRequest}
          onLoadMore={() => {
            void comments.loadMore();
          }}
          onDelete={(commentId): void => {
            comments.removeComment(commentId);
            decreaseCommentCount();
          }}
          onRetry={() => {
            void comments.execute();
          }}
        />
      </main>
      {comments.request.status === 'success' && (
        <FeedCommentInput
          feedId={feedId}
          onCommentCreated={(comment) => {
            comments.addComment(comment);
            increaseCommentCount();
          }}
        />
      )}
    </div>
  );
};

export default FeedDetailContent;

const feedStyle = css`
  padding: 20px 20px 16px;
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
  background-color: ${theme.colors.background.brandWeak};
`;

const authorInformationStyle = css`
  display: flex;
  align-items: center;
  gap: 6px;
  flex: 1;
  min-width: 0;
`;

const authorNameStyle = css`
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-size: 16px;
  font-weight: 700;
`;

const categoryStyle = css`
  display: flex;
  align-items: center;
  gap: 4px;
  flex-shrink: 0;
  font-size: 16px;
  font-weight: 600;
`;

const publishedAtStyle = css`
  flex-shrink: 0;
  color: ${theme.colors.foreground.neutralMuted};
  font-size: 12px;
`;

const comicImageStyle = css`
  -webkit-touch-callout: none;
  display: block;
  width: 100%;
  aspect-ratio: 1;
  object-fit: contain;
`;

const profileImageStyle = css`
  width: 100%;
  height: 100%;
  object-fit: cover;
  border-radius: 50%;
`;

const pageStyle = css`
  display: flex;
  flex-direction: column;
  height: 100%;
  background-color: ${theme.colors.background.surface};
  color: ${theme.colors.foreground.neutral};
`;

const pageHeaderStyle = css`
  position: relative;
  flex-shrink: 0;
  width: 100%;
  height: 56px;
`;

const logoButtonStyle = css`
  display: flex;
  justify-content: center;
  align-items: center;
  width: 100%;
  height: 100%;
  border: none;
  background: none;
  cursor: pointer;
`;

const logoStyle = css`
  width: 106px;
  height: 71px;
`;

const backButtonStyle = css`
  position: absolute;
  top: 8px;
  left: 8px;
  display: flex;
  align-items: center;
  justify-content: center;
  width: 40px;
  height: 40px;
  padding: 0;
  border: none;
  background: none;
  cursor: pointer;
`;

const contentStyle = css`
  flex: 1;
  min-height: 0;
  overflow-y: auto;
`;
