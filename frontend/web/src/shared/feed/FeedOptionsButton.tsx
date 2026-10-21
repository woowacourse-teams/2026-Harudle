import { useToastContext } from '../ToastContext';
import { css } from '@emotion/react';
import { useEffect, useRef } from 'react';
import moreIcon from '../../assets/icons/feed-more.svg';
import deleteIcon from '../../assets/icons/delete.svg';
import type { Feed } from '../../domain/feeds/feedList';
import BottomSheet, { type BottomSheetHandle } from '../../shared/BottomSheet';
import ConfirmModal from '../../shared/ConfirmModal';
import type { ModalHandle } from '../../shared/Modal';
import { theme } from '../../styles/theme';
import { FEED_ACTION_COPY } from './copy';
import useFeedDelete from './useFeedDelete';

interface FeedOptionsButtonProps {
  readonly feedId: Feed['id'];
  readonly onDelete: (feedId: Feed['id']) => void;
}

const FeedOptionsButton = ({ feedId, onDelete }: FeedOptionsButtonProps) => {
  const { addToast } = useToastContext();
  const menuRef = useRef<BottomSheetHandle>(null);
  const confirmRef = useRef<ModalHandle>(null);
  const { request, execute } = useFeedDelete({ feedId });

  useEffect((): void => {
    if (request.status === 'error') {
      addToast({ type: 'error', message: request.error.message }, 2000);
    }
  }, [request]);

  const handleDeleteClick = (): void => {
    menuRef.current?.close();
    confirmRef.current?.open();
  };

  const handleDeleteConfirm = (): void => {
    void execute({
      onSuccess: () => {
        confirmRef.current?.close();
        addToast(
          { type: 'success', message: FEED_ACTION_COPY.deleteSuccess },
          2000,
        );
        onDelete(feedId);
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

      <BottomSheet title={FEED_ACTION_COPY.manageTitle} ref={menuRef}>
        <button
          type="button"
          css={deleteButtonStyle}
          onClick={handleDeleteClick}
        >
          <img src={deleteIcon} alt="휴지통 아이콘" width={24} height={24} />
          {FEED_ACTION_COPY.deleteAction}
        </button>
      </BottomSheet>

      <ConfirmModal
        title={FEED_ACTION_COPY.deleteConfirmTitle}
        description={FEED_ACTION_COPY.deleteConfirmDescription}
        confirmLabel={
          request.status === 'loading'
            ? FEED_ACTION_COPY.deletingAction
            : FEED_ACTION_COPY.deleteAction
        }
        ref={confirmRef}
        onConfirm={handleDeleteConfirm}
      />
    </>
  );
};

export default FeedOptionsButton;

const moreButtonStyle = css`
  display: flex;
  align-items: center;
  justify-content: center;
  width: 28px;
  height: 32px;
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
