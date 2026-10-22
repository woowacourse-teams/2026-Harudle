import { css } from '@emotion/react';
import { useRef, useState } from 'react';
import moreIcon from '../../assets/icons/feed-more.svg';
import type { ReportTarget } from '../../domain/reports/reportSubmission';
import { theme } from '../../styles/theme';
import BottomSheet, { type BottomSheetHandle } from '../BottomSheet';
import NeedLoginBottomSheet from '../NeedLoginBottomSheet';
import ReportDialog, { type ReportDialogHandle } from '../report/ReportDialog';
import { REPORT_COPY, REPORT_TITLES } from '../report/copy';
import BlockDialog, { type BlockDialogHandle } from '../block/BlockDialog';

interface ContentTarget extends ReportTarget {
  readonly targetType: 'FEED' | 'COMMENT';
}
interface ContentOptionsButtonProps {
  readonly target: ContentTarget;
  readonly authorId: string | null;
  readonly authorNickname: string;
}

const ContentOptionsButton = ({
  target,
  authorId,
  authorNickname,
}: ContentOptionsButtonProps) => {
  const menuRef = useRef<BottomSheetHandle>(null);
  const reportRef = useRef<ReportDialogHandle>(null);
  const blockRef = useRef<BlockDialogHandle>(null);
  const loginRef = useRef<BottomSheetHandle>(null);
  const [loginDescription, setLoginDescription] = useState<string>(
    REPORT_COPY.loginDescription,
  );

  const canAct = (description: string): boolean => {
    menuRef.current?.close();
    if (localStorage.getItem('harudle.has-completed-oauth') !== null)
      return true;
    setLoginDescription(description);
    loginRef.current?.open();
    return false;
  };
  const handleReportClick = (selectedTarget: ReportTarget): void => {
    if (canAct(REPORT_COPY.loginDescription))
      reportRef.current?.open(selectedTarget);
  };
  const handleBlockClick = (): void => {
    if (authorId !== null && canAct('작성자를 차단하려면 로그인이 필요해요.')) {
      blockRef.current?.open({ userId: authorId, nickname: authorNickname });
    }
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
      <BottomSheet
        title={target.targetType === 'FEED' ? '피드 메뉴' : '댓글 메뉴'}
        ref={menuRef}
      >
        <button
          type="button"
          css={actionButtonStyle}
          onClick={(): void => handleReportClick(target)}
        >
          {REPORT_TITLES[target.targetType]}
        </button>
        {authorId !== null && (
          <>
            <button
              type="button"
              css={actionButtonStyle}
              onClick={(): void =>
                handleReportClick({ targetType: 'USER', targetId: authorId })
              }
            >
              {REPORT_TITLES.USER}
            </button>
            <button
              type="button"
              css={actionButtonStyle}
              onClick={handleBlockClick}
            >
              작성자 차단
            </button>
          </>
        )}
      </BottomSheet>
      <ReportDialog ref={reportRef} />
      <BlockDialog ref={blockRef} />
      <NeedLoginBottomSheet ref={loginRef} description={loginDescription} />
    </>
  );
};
export default ContentOptionsButton;

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
const actionButtonStyle = css`
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
