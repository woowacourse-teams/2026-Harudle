import { css } from '@emotion/react';
import { useRef } from 'react';
import moreIcon from '../../assets/icons/feed-more.svg';
import deleteIcon from '../../assets/icons/delete.svg';
import type { FeedComment } from '../../domain/feeds/feedComments';
import BottomSheet, { type BottomSheetHandle } from '../../shared/BottomSheet';
import ConfirmModal from '../../shared/ConfirmModal';
import type { ModalHandle } from '../../shared/Modal';
import { theme } from '../../styles/theme';
import { FEED_DETAIL_COPY } from './copy';
import useFeedCommentDelete from './useFeedCommentDelete';

interface OwnFeedCommentOptionsButtonProps {
  readonly commentId: FeedComment['id'];
  readonly onDelete: (commentId: FeedComment['id']) => void;
}

const OwnFeedCommentOptionsButton = ({
  commentId,
  onDelete,
}: OwnFeedCommentOptionsButtonProps) => {
  const menuRef = useRef<BottomSheetHandle>(null);
  const confirmRef = useRef<ModalHandle>(null);
  const { request, reset, execute } = useFeedCommentDelete({ commentId });

  const handleDeleteClick = (): void => {
    menuRef.current?.close();
    reset();
    confirmRef.current?.open();
  };

  const handleDeleteConfirm = (): void => {
    void execute({
      onSuccess: () => {
        confirmRef.current?.close();
        onDelete(commentId);
      },
    });
  };

  return (
    <>
      <button
        type="button"
        css={moreButtonStyle}
        disabled={request.status === 'loading'}
        onClick={() => menuRef.current?.open()}
      >
        <img src={moreIcon} alt="옵션 더보기 아이콘" />
      </button>

      <BottomSheet title={FEED_DETAIL_COPY.commentManageTitle} ref={menuRef}>
        <button
          type="button"
          css={deleteButtonStyle}
          onClick={handleDeleteClick}
        >
          <img src={deleteIcon} alt="휴지통 아이콘" width={24} height={24} />
          {FEED_DETAIL_COPY.commentDeleteAction}
        </button>
      </BottomSheet>

      <ConfirmModal
        errorMessage={
          request.status === 'error' ? request.error.message : undefined
        }
        title={FEED_DETAIL_COPY.commentDeleteConfirmTitle}
        description={FEED_DETAIL_COPY.commentDeleteConfirmDescription}
        confirmLabel={
          request.status === 'loading'
            ? FEED_DETAIL_COPY.commentDeletingAction
            : FEED_DETAIL_COPY.commentDeleteAction
        }
        ref={confirmRef}
        isSubmitting={request.status === 'loading'}
        onConfirm={handleDeleteConfirm}
      />
    </>
  );
};

export default OwnFeedCommentOptionsButton;

const moreButtonStyle = css`
  display: flex;
  align-items: center;
  justify-content: center;
  width: 28px;
  height: 24px;
  padding: 0;
  border: none;
  background: none;
  cursor: pointer;

  &:disabled {
    cursor: wait;
    opacity: 0.6;
  }
`;

const deleteButtonStyle = css`
  display: flex;
  align-items: center;
  gap: 10px;
  width: 100%;
  min-height: 56px;
  padding: 12px 0;
  border: none;
  background: none;
  color: ${theme.colors.foreground.critical};
  text-align: left;
  font-size: 16px;
  line-height: 24px;
  cursor: pointer;
`;
