import { useToastContext } from '../../shared/ToastContext';
import { css } from '@emotion/react';
import { useEffect, useRef } from 'react';
import moreIcon from '../../assets/icons/feed-more.svg';
import deleteIcon from '../../assets/icons/delete.svg';
import BottomSheet, { type BottomSheetHandle } from '../../shared/BottomSheet';
import ConfirmModal from '../../shared/ConfirmModal';
import type { ModalHandle } from '../../shared/Modal';
import { theme } from '../../styles/theme';
import { DIARY_DETAIL_COPY } from './copy';
import useDiaryDelete from './useDiaryDelete';

interface DiaryDetailOptionsButtonProps {
  readonly diaryId: string;
}

const DiaryDetailOptionsButton = ({
  diaryId,
}: DiaryDetailOptionsButtonProps) => {
  const { addToast } = useToastContext();
  const menuRef = useRef<BottomSheetHandle>(null);
  const confirmRef = useRef<ModalHandle>(null);
  const { request, execute: deleteDiary } = useDiaryDelete({ diaryId });

  useEffect((): void => {
    if (request.status === 'error') {
      addToast({ type: 'error', message: request.error.message }, 2000);
    }
  }, [request, addToast]);

  const handleDeleteClick = (): void => {
    menuRef.current?.close();
    confirmRef.current?.open();
  };

  const handleDeleteConfirm = (): void => {
    if (request.status === 'loading') {
      return;
    }

    confirmRef.current?.close();
    void deleteDiary();
  };

  return (
    <>
      <button
        type="button"
        css={menuButtonStyle}
        disabled={request.status === 'loading'}
        onClick={() => menuRef.current?.open()}
      >
        <img src={moreIcon} alt="" width={24} height={24} />
      </button>
      <BottomSheet ref={menuRef} title={DIARY_DETAIL_COPY.menuTitle}>
        <button
          type="button"
          css={deleteButtonStyle}
          onClick={handleDeleteClick}
          disabled={request.status === 'loading'}
        >
          <img src={deleteIcon} alt="휴지통 아이콘" width={24} height={24} />
          {DIARY_DETAIL_COPY.deleteAction}
        </button>
      </BottomSheet>
      <ConfirmModal
        ref={confirmRef}
        title={DIARY_DETAIL_COPY.deleteConfirmTitle}
        description={DIARY_DETAIL_COPY.deleteConfirm}
        confirmLabel={DIARY_DETAIL_COPY.deleteAction}
        onConfirm={handleDeleteConfirm}
      />
    </>
  );
};

export default DiaryDetailOptionsButton;

const menuButtonStyle = css`
  display: flex;
  align-items: center;
  justify-content: center;
  width: 32px;
  height: 32px;
  padding: 0;
  border: none;
  background-color: transparent;
  cursor: pointer;

  &:disabled {
    opacity: 0.5;
    cursor: not-allowed;
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
  background-color: transparent;
  color: ${theme.colors.foreground.critical};
  font-size: 16px;
  line-height: 24px;
  text-align: left;
  cursor: pointer;

  &:disabled {
    opacity: 0.5;
    cursor: not-allowed;
  }
`;
