import { useImperativeHandle, useRef, useState, type Ref } from 'react';
import ConfirmModal from '../ConfirmModal';
import type { ModalHandle } from '../Modal';
import type { ApiRequest } from '../api';
import { setUserBlocked } from '../../domain/blocks/blocks';
import { setFeedListCache } from '../../domain/feeds/feedListCache';
import { BLOCK_COPY, getBlockDescription } from './copy';

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
  const [request, setRequest] = useState<ApiRequest<void>>({ status: 'idle' });
  const isSubmitting = request.status === 'loading';

  useImperativeHandle(
    ref,
    (): BlockDialogHandle => ({
      open: (nextTarget): void => {
        if (submittingRef.current) return;
        setTarget(nextTarget);
        setRequest({ status: 'idle' });
        modalRef.current?.open();
      },
    }),
    [],
  );

  const confirm = async (): Promise<void> => {
    if (!target || submittingRef.current) return;
    submittingRef.current = true;
    setRequest({ status: 'loading' });
    try {
      await setUserBlocked(target.userId, true);
    } catch (error: unknown) {
      submittingRef.current = false;
      setRequest({
        status: 'error',
        error: error instanceof Error ? error : new Error(BLOCK_COPY.failed),
      });
      return;
    }

    setFeedListCache(null);
    // 상세·댓글·알림과 이전 목록의 메모리 상태를 함께 버리고 새 권한으로 조회한다.
    window.location.replace('/');
  };

  return (
    <ConfirmModal
      ref={modalRef}
      title={BLOCK_COPY.title}
      description={target ? getBlockDescription(target.nickname) : ''}
      confirmLabel={isSubmitting ? BLOCK_COPY.submitting : BLOCK_COPY.confirm}
      isSubmitting={isSubmitting}
      errorMessage={
        request.status === 'error' ? request.error.message : undefined
      }
      onConfirm={(): void => {
        void confirm();
      }}
    />
  );
};
export default BlockDialog;
