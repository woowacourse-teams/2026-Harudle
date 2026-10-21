import { useToastContext } from '../../shared/ToastContext';
import { DIARY_DETAIL_COPY } from './copy';
import useDiaryDetail from './useDiaryDetail';
import LoadingSpinner from '../../shared/LoadingSpinner';
import DiaryDetailError from './DiaryDetailError';
import PageHeader from '../../shared/PageHeader';
import backIcon from '../../assets/icons/back.svg';
import sendIcon from '../../assets/icons/feed-send.svg';
import deleteIcon from '../../assets/icons/delete.svg';
import { useNavigate } from 'react-router';
import { css } from '@emotion/react';
import DiaryImageDownloadButton from './DiaryImageDownloadButton';
import { theme } from '../../styles/theme';
import DiaryImage from '../../shared/DiaryImage';
import DiaryImageCopyButton from './DiaryImageCopyButton';
import ActionButton from '../../shared/ActionButton';
import DiaryDetailOptionsButton from './DiaryDetailOptionsButton';
import { useEffect, useRef } from 'react';
import ConfirmModal from '../../shared/ConfirmModal';
import type { ModalHandle } from '../../shared/Modal';
import { FEED_ACTION_COPY } from '../../shared/feed/copy';
import useFeedDelete from '../../shared/feed/useFeedDelete';
import type { Feed } from '../../domain/feeds/feedList';

const DiaryDetailContent = ({ diaryId }: { diaryId: string }) => {
  const navigate = useNavigate();
  const { request: diaryDetailRequest } = useDiaryDetail({ diaryId });

  if (
    diaryDetailRequest.status === 'idle' ||
    diaryDetailRequest.status === 'loading'
  ) {
    return <LoadingSpinner />;
  }

  if (diaryDetailRequest.status === 'error') {
    return <DiaryDetailError errorMessage={diaryDetailRequest.error.message} />;
  }

  const diaryDetail = diaryDetailRequest.data;
  const { publishedFeedId } = diaryDetail;
  const { imageUrl, title } = diaryDetail.generation;

  const handleFeedAction = (): void => {
    void navigate(`/diary/${diaryId}/feed-post`, {
      state: { imageUrl, publishedFeedId },
    });
  };

  return (
    <div css={pageStyle}>
      <PageHeader
        left={
          <button
            type="button"
            aria-label="뒤로 가기"
            css={headerButtonStyle}
            onClick={() => navigate('/album')}
          >
            <img
              src={backIcon}
              alt="뒤로가기 아이콘"
              css={headerButtonIconStyle}
            />
          </button>
        }
        title={diaryDetail.diaryDate}
        right={<DiaryDetailOptionsButton diaryId={diaryId} />}
      />

      <main css={contentStyle}>
        <div css={diaryTitleStyle}>{title}</div>

        <DiaryImage
          diaryId={diaryId}
          imageRole="original"
          css={diaryImageStyle}
          src={imageUrl}
          alt="네컷만화"
        />

        <section css={storyStyle}>
          <h2 css={storyTitleStyle}>{DIARY_DETAIL_COPY.storyTitle}</h2>
          <p css={storyTextStyle}>{diaryDetail.sourceText}</p>
        </section>
        <div css={diaryActionsStyle}>
          {publishedFeedId === null ? (
            <ActionButton
              icon={<span css={sendIconStyle} />}
              label={DIARY_DETAIL_COPY.feedPostAction}
              onClick={handleFeedAction}
            />
          ) : (
            <DiaryFeedDeleteAction feedId={publishedFeedId} />
          )}
          <div css={imageActionsStyle}>
            <DiaryImageDownloadButton
              diaryId={diaryId}
              imageUrl={imageUrl}
              diaryDate={diaryDetail.diaryDate}
              diaryTitle={diaryDetail.generation.title}
            />
            <DiaryImageCopyButton diaryId={diaryId} imageUrl={imageUrl} />
          </div>
        </div>
      </main>
    </div>
  );
};

export default DiaryDetailContent;

const DiaryFeedDeleteAction = ({ feedId }: { feedId: Feed['id'] }) => {
  const { addToast } = useToastContext();
  const navigate = useNavigate();
  const confirmRef = useRef<ModalHandle>(null);
  const { request: feedDeleteRequest, execute: deleteFeed } = useFeedDelete({
    feedId,
  });

  useEffect((): void => {
    if (feedDeleteRequest.status === 'error') {
      addToast(
        { type: 'error', message: feedDeleteRequest.error.message },
        2000,
      );
    }
  }, [feedDeleteRequest]);

  const handleFeedDeleteConfirm = (): void => {
    void deleteFeed({
      onSuccess: (): void => {
        confirmRef.current?.close();
        addToast(
          { type: 'success', message: FEED_ACTION_COPY.deleteSuccess },
          2000,
        );
        void navigate('/');
      },
    });
  };

  return (
    <>
      <ActionButton
        icon={<span css={deleteIconStyle} />}
        label={DIARY_DETAIL_COPY.feedDeleteAction}
        disabled={feedDeleteRequest.status === 'loading'}
        onClick={(): void => confirmRef.current?.open()}
      />
      <ConfirmModal
        ref={confirmRef}
        title={FEED_ACTION_COPY.deleteConfirmTitle}
        description={FEED_ACTION_COPY.deleteConfirmDescription}
        confirmLabel={
          feedDeleteRequest.status === 'loading'
            ? FEED_ACTION_COPY.deletingAction
            : DIARY_DETAIL_COPY.feedDeleteAction
        }
        onConfirm={handleFeedDeleteConfirm}
      />
    </>
  );
};

const pageStyle = css`
  display: flex;
  flex-direction: column;
  width: 100%;
  height: 100%;
  padding: 20px;
  background-color: ${theme.colors.background.surface};
`;

const headerButtonStyle = css`
  display: flex;
  align-items: center;
  justify-content: center;
  width: 32px;
  height: 32px;
  padding: 0;
  border: none;
  background-color: transparent;
  cursor: pointer;
`;

const headerButtonIconStyle = css`
  width: 24px;
  height: 24px;
`;

const contentStyle = css`
  display: flex;
  flex-direction: column;
  gap: 8px;
  width: 100%;
  flex: 1;
  min-height: 0;
  padding-bottom: 20px;
  overflow-y: auto;
`;

const diaryTitleStyle = css`
  flex-shrink: 0;
  color: ${theme.colors.foreground.neutral};
  font-size: 26px;
  font-weight: 700;
  line-height: 36px;
  text-align: center;
  overflow-wrap: break-word;
`;

const diaryActionsStyle = css`
  display: flex;
  flex-direction: column;
  flex-shrink: 0;
  gap: 12px;
`;

const imageActionsStyle = css`
  display: flex;
  gap: 12px;

  & > button {
    flex: 1;
    min-width: 0;
    padding-inline: 8px;
    border-color: ${theme.colors.stroke.brandSolid}55;
  }
`;

const sendIconStyle = css`
  display: block;
  width: 24px;
  height: 24px;
  background-color: currentColor;
  mask: url(${sendIcon}) center / contain no-repeat;
`;

const diaryImageStyle = css`
  flex-shrink: 0;
  width: 100%;
  aspect-ratio: 1;
  border-radius: 16px;
  outline: 1px solid ${theme.colors.stroke.outline};
  background-color: transparent;
  object-fit: cover;
`;

const storyStyle = css`
  flex-shrink: 0;
`;

const storyTitleStyle = css`
  word-break: keep-all;
  color: ${theme.colors.foreground.neutral};
  font-size: 18px;
  font-weight: 700;
  line-height: 28px;
  white-space: pre-wrap;
  overflow-wrap: break-word;
`;

const storyTextStyle = css`
  flex-shrink: 0;
  margin-top: -2px;
  color: ${theme.colors.foreground.neutral};
  font-size: 16px;
  font-weight: 400;
  line-height: 26px;
  white-space: pre-wrap;
  overflow-wrap: break-word;
`;

const deleteIconStyle = css`
  display: block;
  width: 24px;
  height: 24px;
  background-color: currentColor;
  mask: url(${deleteIcon}) center / contain no-repeat;
`;
