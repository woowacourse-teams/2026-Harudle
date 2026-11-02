import { useToastContext } from '../../shared/ToastContext';
import { css } from '@emotion/react';
import { useRef } from 'react';
import { useNavigate } from 'react-router';
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
  const navigate = useNavigate();
  const menuRef = useRef<BottomSheetHandle>(null);
  const confirmRef = useRef<ModalHandle>(null);
  const { request, reset, execute: deleteDiary } = useDiaryDelete({ diaryId });

  const handleDeleteClick = (): void => {
    menuRef.current?.close();
    reset();
    confirmRef.current?.open();
  };

  const handleDeleteConfirm = (): void => {
    if (request.status === 'loading') {
      return;
    }

    void deleteDiary({
      onSuccess: (): void => {
        confirmRef.current?.close();
        addToast(
          { type: 'success', message: DIARY_DETAIL_COPY.deleteSuccess },
          2000,
        );
        void navigate('/album');
      },
    });
  };

  return (
    <>
      <button
        type="button"
        aria-label={DIARY_DETAIL_COPY.menuAction}
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
        errorMessage={
          request.status === 'error' ? request.error.message : undefined
        }
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
