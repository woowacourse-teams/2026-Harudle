import { css } from '@emotion/react';
import { useEffect, useRef } from 'react';
import moreIcon from '../../assets/icons/feed-more.svg';
import deleteIcon from '../../assets/icons/delete.svg';
import type { FeedComment } from '../../domain/feeds/feedComments';
import BottomSheet, { type BottomSheetHandle } from '../../shared/BottomSheet';
import ConfirmModal from '../../shared/ConfirmModal';
import type { ModalHandle } from '../../shared/Modal';
import { theme } from '../../styles/theme';
import ReportDialog, {
  type ReportDialogHandle,
} from '../../shared/report/ReportDialog';
import { REPORT_COPY, REPORT_TITLES } from '../../shared/report/copy';
import type { ReportTarget } from '../../domain/reports/reportSubmission';
import NeedLoginBottomSheet from '../../shared/NeedLoginBottomSheet';
import { FEED_DETAIL_COPY } from './copy';
import useFeedCommentDelete from './useFeedCommentDelete';

interface FeedCommentOptionsButtonProps {
  readonly isMine: boolean;
  readonly authorId: string | null;
  readonly commentId: FeedComment['id'];
  readonly onDelete: (commentId: FeedComment['id']) => void;
}

const FeedCommentOptionsButton = ({
  commentId,
  authorId,
  isMine,
  onDelete,
}: FeedCommentOptionsButtonProps) => {
  const menuRef = useRef<BottomSheetHandle>(null);
  const confirmRef = useRef<ModalHandle>(null);
  const reportRef = useRef<ReportDialogHandle>(null);
  const loginRef = useRef<BottomSheetHandle>(null);
  const { request, execute } = useFeedCommentDelete({ commentId });

  useEffect((): void => {
    if (request.status === 'error') {
      window.alert(request.error.message);
    }
  }, [request]);

  const handleReportClick = (target: ReportTarget): void => {
    menuRef.current?.close();
    if (localStorage.getItem('harudle.has-completed-oauth') === null) {
      loginRef.current?.open();
      return;
    }
    reportRef.current?.open(target);
  };

  const handleDeleteClick = (): void => {
    menuRef.current?.close();
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
        aria-label="댓글 더보기"
        disabled={request.status === 'loading'}
        onClick={() => menuRef.current?.open()}
      >
        <img src={moreIcon} alt="옵션 더보기 아이콘" />
      </button>

      <BottomSheet
        title={
          isMine ? FEED_DETAIL_COPY.commentManageTitle : REPORT_COPY.menuTitle
        }
        ref={menuRef}
      >
        {isMine ? (
          <>
            <button
              type="button"
              css={deleteButtonStyle}
              onClick={handleDeleteClick}
            >
              <img
                src={deleteIcon}
                alt="휴지통 아이콘"
                width={24}
                height={24}
              />
              {FEED_DETAIL_COPY.commentDeleteAction}
            </button>
          </>
        ) : (
          <>
            <button
              type="button"
              css={deleteButtonStyle}
              onClick={(): void =>
                handleReportClick({
                  targetType: 'COMMENT',
                  targetId: commentId,
                })
              }
            >
              {REPORT_TITLES.COMMENT}
            </button>
            {authorId !== null && (
              <button
                type="button"
                css={deleteButtonStyle}
                onClick={(): void =>
                  handleReportClick({ targetType: 'USER', targetId: authorId })
                }
              >
                {REPORT_TITLES.USER}
              </button>
            )}
          </>
        )}
      </BottomSheet>

      <ReportDialog ref={reportRef} />
      <NeedLoginBottomSheet
        ref={loginRef}
        description={REPORT_COPY.loginDescription}
      />

      <ConfirmModal
        title={FEED_DETAIL_COPY.commentDeleteConfirmTitle}
        description={FEED_DETAIL_COPY.commentDeleteConfirmDescription}
        confirmLabel={
          request.status === 'loading'
            ? FEED_DETAIL_COPY.commentDeletingAction
            : FEED_DETAIL_COPY.commentDeleteAction
        }
        ref={confirmRef}
        onConfirm={handleDeleteConfirm}
      />
    </>
  );
};

export default FeedCommentOptionsButton;

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
