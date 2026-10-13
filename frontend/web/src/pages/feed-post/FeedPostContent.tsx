import { css } from '@emotion/react';
import { useEffect, useState, type FormEvent } from 'react';
import sendIcon from '../../assets/icons/feed-send.svg';
import type { Category } from '../../domain/category/categoryList';
import type { DiaryDetailResponse } from '../../domain/diary/diaryDetail';
import ActionButton from '../../shared/ActionButton';
import DiaryImage from '../../shared/DiaryImage';
import { ERROR_MESSAGES } from '../../shared/errorMessage';
import { theme } from '../../styles/theme';
import { FEED_POST_COPY } from './copy';
import useFeedPublish from './useFeedPublish';
import FeedPostCategorySelect from './FeedPostCategorySelect';

interface FeedPostContentProps {
  readonly diaryId: DiaryDetailResponse['id'];
  readonly imageUrl: DiaryDetailResponse['generation']['imageUrl'];
}

const FeedPostContent = ({ diaryId, imageUrl }: FeedPostContentProps) => {
  const { request: publishRequest, execute: publish } = useFeedPublish({
    diaryId,
  });
  const [selectedCategoryId, setSelectedCategoryId] = useState<
    Category['id'] | null
  >(null);

  useEffect((): void => {
    if (publishRequest.status === 'error') {
      window.alert(ERROR_MESSAGES.FEED_PUBLISH_FAILED);
    }
  }, [publishRequest]);

  const isPublishing = publishRequest.status === 'loading';

  const handleCategorySelect = (categoryId: Category['id']): void => {
    setSelectedCategoryId(categoryId);
  };

  const handleSubmit = (event: FormEvent<HTMLFormElement>): void => {
    event.preventDefault();
    if (!selectedCategoryId) return;
    void publish({
      categoryId: selectedCategoryId,
    });
  };

  return (
    <main css={contentStyle}>
      <form css={formStyle} onSubmit={handleSubmit}>
        <div css={previewStyle}>
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
        <div css={publishStyle}>
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
  flex: 1;
  min-height: 0;
  overflow-y: auto;
`;

const formStyle = css`
  display: flex;
  flex-direction: column;
  gap: 28px;
  min-height: 100%;
  padding: 0 20px 32px;
`;

const previewStyle = css`
  display: flex;
  flex-direction: column;
  gap: 28px;
`;

const comicFrameStyle = css`
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

const publishStyle = css`
  display: grid;
  gap: 12px;
  margin-top: auto;
`;

const sendIconStyle = css`
  display: block;
  width: 24px;
  height: 24px;
  background-color: currentColor;
  mask: url(${sendIcon}) center / contain no-repeat;
`;
