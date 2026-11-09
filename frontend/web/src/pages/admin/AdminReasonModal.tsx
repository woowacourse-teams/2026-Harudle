import {
  useImperativeHandle,
  useRef,
  useState,
  useEffect,
  useId,
  type FormEvent,
  type Ref,
} from 'react';
import { css } from '@emotion/react';
import Modal, { type ModalHandle } from '../../shared/Modal';
import ActionButton from '../../shared/ActionButton';
import { RequestError, type ApiRequest } from '../../shared/api';
import { theme } from '../../styles/theme';
import { isValidAdminReason } from './adminModerationApi';
import { ADMIN_MODERATION_COPY as COPY } from './copy';

interface AdminAction {
  readonly title: string;
  readonly description: string;
  readonly execute: (reason: string) => Promise<void>;
  readonly onSuccess: () => void;
  readonly onConflict: () => void;
}
export interface AdminReasonModalHandle {
  readonly open: (action: AdminAction) => void;
}
interface Props {
  readonly ref: Ref<AdminReasonModalHandle>;
}
const AdminReasonModal = ({ ref }: Props) => {
  const hintId = useId();
  const modalRef = useRef<ModalHandle>(null);
  const submittingRef = useRef(false);
  const mountedRef = useRef(true);
  const [action, setAction] = useState<AdminAction | null>(null);
  const [reason, setReason] = useState('');
  const [request, setRequest] = useState<ApiRequest<void>>({ status: 'idle' });
  const [conflict, setConflict] = useState(false);
  const formRef = useRef<HTMLFormElement>(null);
  const pending = request.status === 'loading';
  useEffect((): (() => void) => {
    mountedRef.current = true;
    return (): void => {
      mountedRef.current = false;
    };
  }, []);
  useImperativeHandle(
    ref,
    (): AdminReasonModalHandle => ({
      open: (next): void => {
        if (submittingRef.current) return;
        setAction(next);
        setReason('');
        setConflict(false);
        setRequest({ status: 'idle' });
        modalRef.current?.open();
      },
    }),
    [],
  );
  const submit = async (event: FormEvent<HTMLFormElement>): Promise<void> => {
    event.preventDefault();
    if (
      !action ||
      submittingRef.current ||
      conflict ||
      !isValidAdminReason(reason)
    )
      return;
    submittingRef.current = true;
    setRequest({ status: 'loading' });
    try {
      await action.execute(reason.trim());
    } catch (caught: unknown) {
      if (!mountedRef.current) return;
      const stale =
        caught instanceof RequestError && caught.problem.status === 409;
      setConflict(stale);
      setRequest({
        status: 'error',
        error: stale
          ? new Error(COPY.conflict)
          : caught instanceof Error
            ? caught
            : new Error(COPY.failed),
      });
      if (stale) action.onConflict();
      return;
    } finally {
      submittingRef.current = false;
    }
    if (!mountedRef.current) return;
    setRequest({ status: 'success', data: undefined });
    // 변경 성공과 후속 조회를 분리해 조회 실패가 중복 변경으로 이어지지 않게 한다.
    action.onSuccess();
    // 성공 결과를 확인한 뒤 닫을 수 있도록 안내를 유지한다.
  };
  return (
    <Modal
      ref={modalRef}
      title={action?.title ?? COPY.confirm}
      dismissible={!pending}
      footer={
        request.status === 'success' || conflict ? (
          <ActionButton
            label={COPY.close}
            variant="secondary"
            onClick={(): void => modalRef.current?.close()}
          />
        ) : (
          <>
            <ActionButton
              label={COPY.cancel}
              variant="secondary"
              disabled={pending}
              onClick={(): void => modalRef.current?.close()}
            />
            <ActionButton
              label={pending ? COPY.pending : COPY.confirm}
              disabled={pending || !isValidAdminReason(reason)}
              onClick={(): void => formRef.current?.requestSubmit()}
            />
          </>
        )
      }
    >
      {request.status === 'success' ? (
        <p role="status">{COPY.success}</p>
      ) : (
        <>
          <p>{action?.description}</p>
          <form
            ref={formRef}
            onSubmit={(event): void => {
              void submit(event);
            }}
          >
            <label css={fieldStyle}>
              {COPY.reason}
              <textarea
                value={reason}
                disabled={pending || conflict}
                rows={3}
                aria-describedby={hintId}
                onChange={(event): void => setReason(event.target.value)}
              />
            </label>
            <div css={hintStyle} id={hintId}>
              <span>{COPY.reasonHint}</span>
              <span>{Array.from(reason.trim()).length}/500</span>
            </div>
          </form>
          {request.status === 'error' && (
            <p css={errorStyle} role="alert">
              {request.error.message}
            </p>
          )}
        </>
      )}
    </Modal>
  );
};
export default AdminReasonModal;
const fieldStyle = css`
  display: grid;
  gap: 8px;
  margin: 16px 0 8px;
  textarea {
    width: 100%;
    padding: 12px;
    border: 1px solid ${theme.colors.stroke.outline};
    border-radius: 12px;
    font: inherit;
    resize: vertical;
    color: ${theme.colors.foreground.neutral};
    line-height: 1.5;
    &:focus-visible {
      outline: 3px solid ${theme.colors.stroke.focusRing};
      outline-offset: 2px;
    }
  }
`;
const hintStyle = css`
  display: flex;
  justify-content: space-between;
  gap: 8px;
  font-size: 12px;
  color: ${theme.colors.foreground.neutralMuted};
`;
const errorStyle = css`
  color: ${theme.colors.foreground.neutral};
  background: ${theme.colors.background.criticalWeak};
  border-left: 3px solid ${theme.colors.stroke.critical};
  border-radius: 8px;
  padding: 10px 12px;
  margin-top: 12px;
`;
