import { css } from '@emotion/react';
import {
  useEffect,
  useId,
  useImperativeHandle,
  useRef,
  useState,
  type FormEvent,
  type Ref,
  type ReactElement,
} from 'react';
import {
  isValidReportDescription,
  REPORT_ERRORS,
  submitReport,
  type ReportReason,
  type ReportTarget,
} from '../../domain/reports/reportSubmission';
import type { ApiRequest } from '../api';
import Modal, { type ModalHandle } from '../Modal';
import { useToastContext } from '../ToastContext';
import { theme } from '../../styles/theme';
import { REPORT_COPY, REPORT_REASONS, REPORT_TITLES } from './copy';

interface ReportDialogProps {
  readonly ref: Ref<ModalHandle>;
  readonly target: ReportTarget;
}

interface ReportFormValues {
  readonly reason: ReportReason | '';
  readonly description: string;
}

const ReportDialog = ({ ref, target }: ReportDialogProps) => {
  const modalRef = useRef<ModalHandle>(null);
  const submittingRef = useRef(false);
  const mountedRef = useRef(false);
  const targetVersionRef = useRef(0);
  const { addToast } = useToastContext();
  const formId = useId();
  const [form, setForm] = useState<ReportFormValues>({
    reason: '',
    description: '',
  });
  const [request, setRequest] = useState<ApiRequest<void>>({
    status: 'idle',
  });
  const isSubmitting = request.status === 'loading';
  const isDescriptionValid = isValidReportDescription(form.description);

  useEffect((): (() => void) => {
    mountedRef.current = true;
    return (): void => {
      mountedRef.current = false;
    };
  }, []);

  useEffect((): void => {
    targetVersionRef.current += 1;
    // 전송 중 대상이 바뀐 경우에만 이전 폼을 닫고 응답을 새 신고에 적용하지 않는다.
    if (submittingRef.current) modalRef.current?.close();
  }, [target.targetType, target.targetId]);

  const initializeReport = (): void => {
    setForm({ reason: '', description: '' });
    setRequest({ status: 'idle' });
  };

  useImperativeHandle(ref, (): ModalHandle => ({
    open: (): void => {
      if (submittingRef.current) return;
      initializeReport();
      modalRef.current?.open();
    },
    close: (): void => {
      if (submittingRef.current) return;
      modalRef.current?.close();
    },
  }));

  const handleSubmit = async (
    event: FormEvent<HTMLFormElement>,
  ): Promise<void> => {
    event.preventDefault();
    if (!form.reason || !isDescriptionValid || submittingRef.current) return;
    submittingRef.current = true;
    const targetVersion = targetVersionRef.current;
    setRequest({ status: 'loading' });
    try {
      const response = await submitReport({
        ...target,
        reason: form.reason,
        description: form.description,
      });
      if (!mountedRef.current || targetVersion !== targetVersionRef.current)
        return;
      setRequest({ status: 'idle' });
      modalRef.current?.close();
      addToast(
        {
          type: 'success',
          message: response.duplicate
            ? REPORT_COPY.duplicate
            : REPORT_COPY.received,
        },
        3000,
      );
    } catch (error: unknown) {
      if (!mountedRef.current || targetVersion !== targetVersionRef.current)
        return;
      setRequest({
        status: 'error',
        error: error instanceof Error ? error : new Error(REPORT_ERRORS.failed),
      });
    } finally {
      submittingRef.current = false;
      if (mountedRef.current && targetVersion !== targetVersionRef.current)
        setRequest({ status: 'idle' });
    }
  };

  return (
    <Modal
      ref={modalRef}
      dismissible={!isSubmitting}
      title={REPORT_TITLES[target.targetType]}
      footer={
        <>
          <button
            type="button"
            css={cancelStyle}
            disabled={isSubmitting}
            onClick={(): void => modalRef.current?.close()}
          >
            {REPORT_COPY.cancel}
          </button>
          <button
            type="submit"
            form={formId}
            css={submitStyle}
            disabled={!form.reason || !isDescriptionValid || isSubmitting}
          >
            {isSubmitting ? REPORT_COPY.submitting : REPORT_COPY.submit}
          </button>
        </>
      }
    >
      <form
        id={formId}
        onSubmit={(event): void => {
          void handleSubmit(event);
        }}
      >
        <fieldset disabled={isSubmitting} css={reasonStyle}>
          <legend css={labelStyle}>{REPORT_COPY.reason}</legend>
          {REPORT_REASONS.map((option): ReactElement => (
            <label css={optionStyle} key={option.value}>
              <input
                type="radio"
                name={formId}
                value={option.value}
                checked={form.reason === option.value}
                onChange={(): void => {
                  setForm((current): ReportFormValues => ({
                    ...current,
                    reason: option.value,
                  }));
                  setRequest({ status: 'idle' });
                }}
              />
              {option.label}
            </label>
          ))}
        </fieldset>
        <label css={labelStyle} htmlFor={`${formId}-description`}>
          {REPORT_COPY.description}
        </label>
        <textarea
          id={`${formId}-description`}
          rows={4}
          css={descriptionStyle}
          value={form.description}
          disabled={isSubmitting}
          aria-describedby={`${formId}-hint`}
          onChange={(event): void => {
            const description = event.target.value;
            setForm((current): ReportFormValues => ({
              ...current,
              description,
            }));
            setRequest({ status: 'idle' });
          }}
        />
        <p id={`${formId}-hint`} css={hintStyle}>
          {REPORT_COPY.descriptionHint}
        </p>
        <p css={hintStyle}>
          {Array.from(form.description.trim()).length} / 500
        </p>
        {!isDescriptionValid && (
          <p role="alert" css={errorStyle}>
            {REPORT_ERRORS.description}
          </p>
        )}
        {request.status === 'error' && (
          <p role="alert" css={errorStyle}>
            {request.error.message}
          </p>
        )}
      </form>
    </Modal>
  );
};
export default ReportDialog;

const reasonStyle = css`
  margin-top: 12px;
`;
const labelStyle = css`
  display: block;
  margin: 12px 0 8px;
  font-weight: 700;
  color: ${theme.colors.foreground.neutral};
`;
const optionStyle = css`
  display: flex;
  align-items: center;
  gap: 10px;
  min-height: 40px;
  cursor: pointer;
`;
const descriptionStyle = css`
  width: 100%;
  padding: 10px 12px;
  border: 1px solid ${theme.colors.stroke.outline};
  border-radius: 8px;
  color: ${theme.colors.foreground.neutral};
  background: ${theme.colors.background.surface};
  resize: vertical;
  font-size: 16px;
  line-height: 24px;
`;
const hintStyle = css`
  margin-top: 8px;
  font-size: 12px;
  line-height: 18px;
`;
const errorStyle = css`
  ${hintStyle};
  color: ${theme.colors.foreground.critical};
`;
const buttonStyle = css`
  flex: 1;
  min-height: 44px;
  padding: 10px;
  border: 0;
  border-radius: 8px;
  font-size: 14px;
  cursor: pointer;
  &:disabled {
    cursor: default;
    opacity: 0.5;
  }
`;
const cancelStyle = css`
  ${buttonStyle};
  background: ${theme.colors.background.neutralSubtle};
  color: ${theme.colors.foreground.neutral};
`;
const submitStyle = css`
  ${buttonStyle};
  background: ${theme.colors.background.brandSolid};
  color: ${theme.colors.foreground.onBrand};
`;
