import { css } from '@emotion/react';
import { useRef } from 'react';
import moreIcon from '../../assets/icons/feed-more.svg';
import type { ReportTarget } from '../../domain/reports/reportSubmission';
import { theme } from '../../styles/theme';
import BottomSheet, { type BottomSheetHandle } from '../BottomSheet';
import NeedLoginBottomSheet from '../NeedLoginBottomSheet';
import ReportDialog, { type ReportDialogHandle } from './ReportDialog';
import { REPORT_COPY, REPORT_TITLES } from './copy';

interface ReportContentTarget extends ReportTarget {
  readonly targetType: 'FEED' | 'COMMENT';
}

interface ReportOptionsButtonProps {
  readonly target: ReportContentTarget;
  readonly authorId: string | null;
}

const ReportOptionsButton = ({
  target,
  authorId,
}: ReportOptionsButtonProps) => {
  const menuRef = useRef<BottomSheetHandle>(null);
  const reportRef = useRef<ReportDialogHandle>(null);
  const loginRef = useRef<BottomSheetHandle>(null);

  const handleReportClick = (selectedTarget: ReportTarget): void => {
    menuRef.current?.close();
    if (localStorage.getItem('harudle.has-completed-oauth') === null) {
      loginRef.current?.open();
      return;
    }
    reportRef.current?.open(selectedTarget);
  };

  return (
    <>
      <button
        type="button"
        css={[
          moreButtonStyle,
          target.targetType === 'COMMENT' && commentMoreButtonStyle,
        ]}
        aria-label={
          target.targetType === 'FEED' ? '피드 더보기' : '댓글 더보기'
        }
        onClick={(): void => menuRef.current?.open()}
      >
        <img src={moreIcon} alt="옵션 더보기 아이콘" />
      </button>
      <BottomSheet title={REPORT_COPY.menuTitle} ref={menuRef}>
        <button
          type="button"
          css={reportButtonStyle}
          onClick={(): void => handleReportClick(target)}
        >
          {REPORT_TITLES[target.targetType]}
        </button>
        {authorId !== null && (
          <button
            type="button"
            css={reportButtonStyle}
            onClick={(): void =>
              handleReportClick({ targetType: 'USER', targetId: authorId })
            }
          >
            {REPORT_TITLES.USER}
          </button>
        )}
      </BottomSheet>
      <ReportDialog ref={reportRef} />
      <NeedLoginBottomSheet
        ref={loginRef}
        description={REPORT_COPY.loginDescription}
      />
    </>
  );
};

export default ReportOptionsButton;

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
`;

const commentMoreButtonStyle = css`
  height: 24px;
`;

const reportButtonStyle = css`
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
