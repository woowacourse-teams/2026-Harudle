import { css } from '@emotion/react';
import { useEffect, useRef } from 'react';
import moreIcon from '../../assets/icons/feed-more.svg';
import deleteIcon from '../../assets/icons/delete.svg';
import type { Feed } from '../../domain/feeds/feedList';
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
import { FEED_ACTION_COPY } from './copy';
import useFeedDelete from './useFeedDelete';

interface FeedOptionsButtonProps {
  readonly isMine: boolean;
  readonly authorId: string;
  readonly feedId: Feed['id'];
  readonly onDelete: (feedId: Feed['id']) => void;
}

const FeedOptionsButton = ({
  feedId,
  authorId,
  isMine,
  onDelete,
}: FeedOptionsButtonProps) => {
  const menuRef = useRef<BottomSheetHandle>(null);
  const confirmRef = useRef<ModalHandle>(null);
  const reportRef = useRef<ReportDialogHandle>(null);
  const loginRef = useRef<BottomSheetHandle>(null);
  const { request, execute } = useFeedDelete({ feedId });

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
        onDelete(feedId);
      },
    });
  };

  return (
    <>
      <button
        type="button"
        css={moreButtonStyle}
        aria-label="피드 더보기"
        disabled={request.status === 'loading'}
        onClick={() => menuRef.current?.open()}
      >
        <img src={moreIcon} alt="옵션 더보기 아이콘" />
      </button>

      <BottomSheet
        title={isMine ? FEED_ACTION_COPY.manageTitle : REPORT_COPY.menuTitle}
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
              {FEED_ACTION_COPY.deleteAction}
            </button>
          </>
        ) : (
          <>
            <button
              type="button"
              css={deleteButtonStyle}
              onClick={(): void =>
                handleReportClick({ targetType: 'FEED', targetId: feedId })
              }
            >
              {REPORT_TITLES.FEED}
            </button>
            <button
              type="button"
              css={deleteButtonStyle}
              onClick={(): void =>
                handleReportClick({ targetType: 'USER', targetId: authorId })
              }
            >
              {REPORT_TITLES.USER}
            </button>
          </>
        )}
      </BottomSheet>

      <ReportDialog ref={reportRef} />
      <NeedLoginBottomSheet
        ref={loginRef}
        description={REPORT_COPY.loginDescription}
      />

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
