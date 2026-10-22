import { css } from '@emotion/react';
import { useImperativeHandle, useRef, useState, type Ref } from 'react';
import Modal, { type ModalHandle } from '../Modal';
import { setUserBlocked } from '../../domain/blocks/blocks';
import { setFeedListCache } from '../../domain/feeds/feedListCache';
import { theme } from '../../styles/theme';

interface BlockTarget {
  readonly userId: string;
  readonly nickname: string;
}
export interface BlockDialogHandle {
  readonly open: (target: BlockTarget) => void;
}
interface BlockDialogProps {
  readonly ref: Ref<BlockDialogHandle>;
}

const BlockDialog = ({ ref }: BlockDialogProps) => {
  const modalRef = useRef<ModalHandle>(null);
  const submittingRef = useRef(false);
  const [target, setTarget] = useState<BlockTarget | null>(null);
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);
  useImperativeHandle(
    ref,
    (): BlockDialogHandle => ({
      open: (nextTarget): void => {
        if (submittingRef.current) return;
        setTarget(nextTarget);
        setError(null);
        modalRef.current?.open();
      },
    }),
    [],
  );

  const confirm = async (): Promise<void> => {
    if (!target || submittingRef.current) return;
    submittingRef.current = true;
    setSubmitting(true);
    setError(null);
    try {
      await setUserBlocked(target.userId, true);
      setFeedListCache(null);
      // 상세·댓글·알림과 이전 목록의 메모리 상태를 함께 버리고 새 권한으로 조회한다.
      window.location.replace('/');
    } catch (caught: unknown) {
      setError(
        caught instanceof Error
          ? caught.message
          : '차단하지 못했어요. 다시 시도해주세요.',
      );
      submittingRef.current = false;
      setSubmitting(false);
    }
  };

  return (
    <Modal
      ref={modalRef}
      title="작성자 차단 확인"
      dismissible={!submitting}
      footer={
        target !== null && (
          <>
            <button
              type="button"
              css={buttonStyle}
              disabled={submitting}
              onClick={(): void => modalRef.current?.close()}
            >
              취소
            </button>
            <button
              type="button"
              css={[buttonStyle, confirmStyle]}
              disabled={submitting}
              onClick={(): void => {
                void confirm();
              }}
            >
              {submitting ? '차단 중…' : '차단하기'}
            </button>
          </>
        )
      }
    >
      {target !== null && (
        <>
          <p css={targetStyle}>{target.nickname}님을 차단할까요?</p>
          <p css={descriptionStyle}>
            로그인한 동안 서로의 피드와 댓글이 보이지 않고, 댓글·좋아요·새
            알림을 주고받을 수 없어요. 로그아웃하면 공개 콘텐츠는 볼 수 있어요.
          </p>
          <p css={descriptionStyle}>
            설정의 차단 목록에서 내가 한 차단을 해제할 수 있어요.
          </p>
          {error && (
            <p role="alert" css={errorStyle}>
              {error}
            </p>
          )}
        </>
      )}
    </Modal>
  );
};
export default BlockDialog;
const targetStyle = css`
  color: ${theme.colors.foreground.neutral};
`;
const descriptionStyle = css`
  padding-top: 12px;
  word-break: keep-all;
`;
const errorStyle = css`
  padding-top: 12px;
  color: ${theme.colors.foreground.critical};
`;
const buttonStyle = css`
  flex: 1;
  min-height: 44px;
  padding: 12px;
  border: 0;
  border-radius: 8px;
  background: ${theme.colors.background.neutralSubtle};
  color: ${theme.colors.foreground.neutral};
  font-size: 14px;
  cursor: pointer;
  &:disabled {
    opacity: 0.5;
    cursor: default;
  }
`;
const confirmStyle = css`
  background: ${theme.colors.background.criticalSolid};
  color: ${theme.colors.foreground.onBrand};
`;
