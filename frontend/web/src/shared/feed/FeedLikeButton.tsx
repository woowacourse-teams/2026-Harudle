import { useToastContext } from '../ToastContext';
import { css, keyframes } from '@emotion/react';
import { useEffect, useRef } from 'react';
import heartIcon from '../../assets/icons/feed-heart.svg';
import filledHeartIcon from '../../assets/icons/feed-heart-filled.svg';
import type { Feed } from '../../domain/feeds/feedList';
import type { FeedLikeResponse } from '../../domain/feeds/feedLike';
import type { BottomSheetHandle } from '../../shared/BottomSheet';
import NeedLoginBottomSheet from '../../shared/NeedLoginBottomSheet';
import { FEED_ACTION_COPY } from './copy';
import { theme } from '../../styles/theme';
import useFeedLike from './useFeedLike';

interface FeedLikeButtonProps extends Pick<Feed, 'likeCount' | 'likedByMe'> {
  readonly feedId: Feed['id'];
  readonly onLikeChange: (feedId: Feed['id'], like: FeedLikeResponse) => void;
}

const FeedLikeButton = ({
  feedId,
  likeCount,
  likedByMe,
  onLikeChange,
}: FeedLikeButtonProps) => {
  const { addToast } = useToastContext();
  const { request, execute } = useFeedLike({ feedId });
  const loginSheetRef = useRef<BottomSheetHandle>(null);

  useEffect(() => {
    if (request.status === 'error') {
      addToast({ type: 'error', message: request.error.message }, 2000);
    }
  }, [request, addToast]);

  const handleLike = (): void => {
    if (request.status === 'loading') return;

    if (localStorage.getItem('harudle.has-completed-oauth') === null) {
      loginSheetRef.current?.open();
      return;
    }

    void execute({
      likedByMe: !likedByMe,
      onSuccess: (like) => onLikeChange(feedId, like),
    });
  };

  return (
    <>
      <button
        type="button"
        aria-disabled={request.status === 'loading'}
        aria-pressed={likedByMe}
        css={likeButtonStyle}
        onClick={handleLike}
        aria-label={`좋아요 ${likeCount}개`}
      >
        <img
          src={likedByMe ? filledHeartIcon : heartIcon}
          alt=""
          width={24}
          height={24}
          css={
            likedByMe &&
            request.status === 'success' &&
            request.data.likedByMe &&
            likedHeartStyle
          }
        />
        <span>{likeCount}</span>
      </button>
      <NeedLoginBottomSheet
        description={FEED_ACTION_COPY.likeLoginDescription}
        ref={loginSheetRef}
      />
    </>
  );
};

export default FeedLikeButton;

const heartPop = keyframes`
  0% { transform: scale(1); }
  35% { transform: scale(1.1) rotate(-4deg); }
  65% { transform: scale(0.8) rotate(4deg); }
  100% { transform: scale(1); }
`;

const likedHeartStyle = css`
  animation: ${heartPop} 350ms ease-out;

  @media (prefers-reduced-motion: reduce) {
    animation: none;
  }
`;

const likeButtonStyle = css`
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

  &[aria-disabled='true'] {
    opacity: 0.6;
  }
`;
