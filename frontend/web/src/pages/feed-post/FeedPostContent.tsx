import { useToastContext } from '../../shared/ToastContext';
import { css } from '@emotion/react';
import { useState, type FormEvent } from 'react';
import { useNavigate } from 'react-router';
import sendIcon from '../../assets/icons/feed-send.svg';
import type { Category } from '../../domain/category/categoryList';
import type { DiaryDetailResponse } from '../../domain/diary/diaryDetail';
import type { DiaryGenerationSuccess } from '../../domain/diary/diaryGeneration';
import ActionButton from '../../shared/ActionButton';
import DiaryImage from '../../shared/DiaryImage';
import { ERROR_MESSAGES } from '../../shared/errorMessage';
import { theme } from '../../styles/theme';
import { FEED_POST_COPY } from './copy';
import useFeedPublish from './useFeedPublish';
import FeedPostCategorySelect from './FeedPostCategorySelect';

interface FeedPostContentProps {
  readonly diaryId: DiaryDetailResponse['id'];
  readonly imageUrl: DiaryGenerationSuccess['imageUrl'];
}

const FeedPostContent = ({ diaryId, imageUrl }: FeedPostContentProps) => {
  const { addToast } = useToastContext();
  const navigate = useNavigate();
  const { request: publishRequest, execute: publish } = useFeedPublish({
    diaryId,
  });
  const [selectedCategoryId, setSelectedCategoryId] = useState<
    Category['id'] | null
  >(null);

  const isPublishing = publishRequest.status === 'loading';

  const handleCategorySelect = (categoryId: Category['id']): void => {
    setSelectedCategoryId(categoryId);
  };

  const handleSubmit = (event: FormEvent<HTMLFormElement>): void => {
    event.preventDefault();
    if (!selectedCategoryId) return;
    void publish({
      categoryId: selectedCategoryId,
      onSuccess: (): void => {
        addToast(
          { type: 'success', message: FEED_POST_COPY.publishSuccess },
          2000,
        );
        void navigate('/', { replace: true });
      },
      onError: (): void => {
        addToast(
          { type: 'error', message: ERROR_MESSAGES.FEED_PUBLISH_FAILED },
          2000,
        );
      },
    });
  };

  return (
    <main css={contentStyle}>
      <form css={formStyle} onSubmit={handleSubmit}>
        <div css={scrollStyle}>
          <div css={comicFrameStyle}>
            <DiaryImage
              diaryId={diaryId}
              imageRole="original"
              css={comicStyle}
              src={imageUrl}
              alt="네컷만화"
            />
          </div>
          <FeedPostCategorySelect
            selectedCategoryId={selectedCategoryId}
            onSelect={handleCategorySelect}
            disabled={isPublishing}
          />
        </div>
        <div css={footerStyle}>
          <ActionButton
            type="submit"
            icon={<span css={sendIconStyle} />}
            label={
              isPublishing
                ? FEED_POST_COPY.publishingAction
                : FEED_POST_COPY.publishAction
            }
            disabled={!selectedCategoryId || isPublishing}
          />
        </div>
      </form>
    </main>
  );
};

export default FeedPostContent;

const contentStyle = css`
  display: flex;
  flex: 1;
  flex-direction: column;
  min-height: 0;
`;

const formStyle = css`
  display: flex;
  flex: 1;
  flex-direction: column;
  min-height: 0;
`;

const scrollStyle = css`
  display: flex;
  flex: 1;
  flex-direction: column;
  gap: 28px;
  min-height: 0;
  overflow-y: auto;
  padding: 0 20px 24px;
`;

const comicFrameStyle = css`
  flex-shrink: 0;
  width: 100%;
  aspect-ratio: 1;
  padding: 6px;
  border: 1px solid ${theme.colors.stroke.outline};
  border-radius: 16px;
  overflow: hidden;
`;

const comicStyle = css`
  display: block;
  width: 100%;
  height: 100%;
  object-fit: contain;
`;

const footerStyle = css`
  flex-shrink: 0;
  padding: 12px 20px max(12px, env(safe-area-inset-bottom));
  border-top: 1px solid ${theme.colors.stroke.divider};
  background: ${theme.colors.background.surface};
`;

const sendIconStyle = css`
  display: block;
  width: 24px;
  height: 24px;
  background-color: currentColor;
  mask: url(${sendIcon}) center / contain no-repeat;
`;
