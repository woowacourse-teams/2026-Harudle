import { css } from '@emotion/react';
import {
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
  type ReportReceipt,
  type ReportTarget,
} from '../../domain/reports/reportSubmission';
import type { ApiRequest } from '../api';
import Modal, { type ModalHandle } from '../Modal';
import { theme } from '../../styles/theme';
import { REPORT_COPY, REPORT_REASONS, REPORT_TITLES } from './copy';

export interface ReportDialogHandle {
  readonly open: (target: ReportTarget) => void;
}

interface ReportDialogProps {
  readonly ref: Ref<ReportDialogHandle>;
}

const ReportDialog = ({ ref }: ReportDialogProps) => {
  const modalRef = useRef<ModalHandle>(null);
  const submittingRef = useRef(false);
  const formId = useId();
  const [target, setTarget] = useState<ReportTarget | null>(null);
  const [reason, setReason] = useState<ReportReason | ''>('');
  const [description, setDescription] = useState('');
  const [request, setRequest] = useState<ApiRequest<ReportReceipt>>({
    status: 'idle',
  });
  const isSubmitting = request.status === 'loading';
  const isDescriptionValid = isValidReportDescription(description);

  useImperativeHandle(
    ref,
    (): ReportDialogHandle => ({
      open: (nextTarget): void => {
        if (submittingRef.current) return;
        setTarget(nextTarget);
        setReason('');
        setDescription('');
        setRequest({ status: 'idle' });
        modalRef.current?.open();
      },
    }),
    [],
  );

  const handleSubmit = async (
    event: FormEvent<HTMLFormElement>,
  ): Promise<void> => {
    event.preventDefault();
    if (!target || !reason || !isDescriptionValid || submittingRef.current)
      return;
    submittingRef.current = true;
    setRequest({ status: 'loading' });
    try {
      const receipt = await submitReport({ ...target, reason, description });
      setRequest({ status: 'success', data: receipt });
    } catch (error: unknown) {
      setRequest({
        status: 'error',
        error: error instanceof Error ? error : new Error(REPORT_ERRORS.failed),
      });
    } finally {
      submittingRef.current = false;
    }
  };

  return (
    <Modal
      ref={modalRef}
      dismissible={!isSubmitting}
      title={target ? REPORT_TITLES[target.targetType] : REPORT_COPY.menuTitle}
      footer={
        target !== null &&
        (request.status === 'success' ? (
          <button
            type="button"
            css={submitStyle}
            onClick={(): void => modalRef.current?.close()}
          >
            {REPORT_COPY.close}
          </button>
        ) : (
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
              disabled={!reason || !isDescriptionValid || isSubmitting}
            >
              {isSubmitting ? REPORT_COPY.submitting : REPORT_COPY.submit}
            </button>
          </>
        ))
      }
    >
      {target !== null &&
        (request.status === 'success' ? (
          <p role="status">
            {request.data.duplicate
              ? REPORT_COPY.duplicate
              : REPORT_COPY.received}
          </p>
        ) : (
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
                    checked={reason === option.value}
                    onChange={(): void => {
                      setReason(option.value);
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
              value={description}
              disabled={isSubmitting}
              aria-describedby={`${formId}-hint`}
              onChange={(event): void => {
                setDescription(event.target.value);
                setRequest({ status: 'idle' });
              }}
            />
            <p id={`${formId}-hint`} css={hintStyle}>
              {REPORT_COPY.descriptionHint}
            </p>
            <p css={hintStyle}>{Array.from(description.trim()).length} / 500</p>
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
        ))}
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
