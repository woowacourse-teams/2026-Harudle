import { css } from '@emotion/react';
import { useRef, useState } from 'react';
import moreIcon from '../../assets/icons/feed-more.svg';
import reportIcon from '../../assets/icons/report.svg';
import blockIcon from '../../assets/icons/block.svg';
import type { Feed } from '../../domain/feeds/feedList';
import type { ReportTarget } from '../../domain/reports/reportSubmission';
import { setFeedListCache } from '../../domain/feeds/feedListCache';
import { theme } from '../../styles/theme';
import BottomSheet, { type BottomSheetHandle } from '../BottomSheet';
import NeedLoginBottomSheet from '../NeedLoginBottomSheet';
import ConfirmModal from '../ConfirmModal';
import type { ModalHandle } from '../Modal';
import ReportDialog from '../report/ReportDialog';
import { REPORT_COPY, REPORT_TITLES } from '../report/copy';
import { BLOCK_COPY, getBlockDescription } from '../block/copy';
import useBlockUser from '../block/useBlockUser';

interface OtherFeedOptionsButtonProps {
  readonly feedId: Feed['id'];
  readonly authorId: string | null;
  readonly authorNickname: string;
}

const OtherFeedOptionsButton = ({
  feedId,
  authorId,
  authorNickname,
}: OtherFeedOptionsButtonProps) => {
  const menuRef = useRef<BottomSheetHandle>(null);
  const reportRef = useRef<ModalHandle>(null);
  const blockConfirmRef = useRef<ModalHandle>(null);
  const loginRef = useRef<BottomSheetHandle>(null);
  const [selectedReportTarget, setSelectedReportTarget] =
    useState<ReportTarget>({
      targetType: 'FEED',
      targetId: feedId,
    });
  const [blockTarget, setBlockTarget] = useState<
    Pick<OtherFeedOptionsButtonProps, 'authorId' | 'authorNickname'>
  >({ authorId, authorNickname });
  const [loginDescription, setLoginDescription] = useState<string>(
    REPORT_COPY.loginDescription,
  );
  const { request, reset, execute } = useBlockUser({
    userId: blockTarget.authorId,
  });
  const isBlocking = request.status === 'loading';

  const canAct = (description: string): boolean => {
    menuRef.current?.close();
    if (localStorage.getItem('harudle.has-completed-oauth') !== null)
      return true;
    setLoginDescription(description);
    loginRef.current?.open();
    return false;
  };
  const handleReportClick = (target: ReportTarget): void => {
    if (isBlocking || !canAct(REPORT_COPY.loginDescription)) return;
    setSelectedReportTarget(target);
    reportRef.current?.open();
  };
  const handleBlockClick = (): void => {
    if (
      isBlocking ||
      authorId === null ||
      !canAct('작성자를 차단하려면 로그인이 필요해요.')
    )
      return;
    setBlockTarget({ authorId, authorNickname });
    reset();
    blockConfirmRef.current?.open();
  };
  const handleBlockConfirm = (): void => {
    void execute({
      onSuccess: (): void => {
        setFeedListCache(null);
        window.location.replace('/');
      },
    });
  };

  return (
    <>
      <button
        type="button"
        css={moreButtonStyle}
        aria-label="피드 더보기"
        disabled={isBlocking}
        onClick={(): void => menuRef.current?.open()}
      >
        <img src={moreIcon} alt="옵션 더보기 아이콘" />
      </button>
      <BottomSheet title="피드 메뉴" ref={menuRef}>
        <button
          type="button"
          css={actionButtonStyle}
          onClick={(): void =>
            handleReportClick({ targetType: 'FEED', targetId: feedId })
          }
        >
          <img
            src={reportIcon}
            alt=""
            aria-hidden="true"
            width={24}
            height={24}
          />
          {REPORT_TITLES.FEED}
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
              <img
                src={reportIcon}
                alt=""
                aria-hidden="true"
                width={24}
                height={24}
              />
              {REPORT_TITLES.USER}
            </button>
            <button
              type="button"
              css={actionButtonStyle}
              onClick={handleBlockClick}
            >
              <img
                src={blockIcon}
                alt=""
                aria-hidden="true"
                width={24}
                height={24}
              />
              작성자 차단
            </button>
          </>
        )}
      </BottomSheet>
      <ReportDialog ref={reportRef} target={selectedReportTarget} />
      <ConfirmModal
        ref={blockConfirmRef}
        title={BLOCK_COPY.title}
        description={getBlockDescription(blockTarget.authorNickname)}
        confirmLabel={isBlocking ? BLOCK_COPY.submitting : BLOCK_COPY.confirm}
        isSubmitting={isBlocking}
        errorMessage={
          request.status === 'error' ? request.error.message : undefined
        }
        onConfirm={handleBlockConfirm}
      />
      <NeedLoginBottomSheet ref={loginRef} description={loginDescription} />
    </>
  );
};

export default OtherFeedOptionsButton;

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
