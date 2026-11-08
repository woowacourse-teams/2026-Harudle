import { css } from '@emotion/react';
import { useId, useState, type FormEvent, type Ref } from 'react';
import ActionButton from '../../../shared/ActionButton';
import BottomSheet, {
  type BottomSheetHandle,
} from '../../../shared/BottomSheet';
import { theme } from '../../../styles/theme';
import {
  getKoreanToday,
  validateGuestDiary,
} from '../../landing/guestDiaryValidation';
import type { SubmitGuestTrial } from './useGuestTrialSubmit';
import { GUEST_TRIAL_COPY } from './copy';

const MAX_SOURCE_TEXT_LENGTH = 300;

interface GuestTrialInputSheetProps {
  readonly ref: Ref<BottomSheetHandle>;
  readonly onSubmit: SubmitGuestTrial;
}

/** 시트를 닫아도 컴포넌트가 남아 있어 같은 피드 방문 동안 초안을 유지한다. */
const GuestTrialInputSheet = ({ ref, onSubmit }: GuestTrialInputSheetProps) => {
  const fieldId = useId();
  const errorId = useId();
  const [sourceText, setSourceText] = useState('');
  const [errorMessage, setErrorMessage] = useState<string | null>(null);
  const [isSubmitting, setIsSubmitting] = useState(false);

  const handleSubmit = async (
    event: FormEvent<HTMLFormElement>,
  ): Promise<void> => {
    event.preventDefault();
    if (isSubmitting) return;

    const trimmedText = sourceText.trim();
    const validationError = validateGuestDiary({
      diaryDate: getKoreanToday(),
      sourceText: trimmedText,
    }).sourceText;
    if (validationError) {
      setErrorMessage(validationError);
      return;
    }

    setIsSubmitting(true);
    setErrorMessage(null);
    const submitError = await onSubmit(trimmedText);
    setIsSubmitting(false);
    setErrorMessage(submitError);
  };

  return (
    <BottomSheet title={GUEST_TRIAL_COPY.inputSheetTitle} ref={ref}>
      <form css={formStyle} noValidate onSubmit={handleSubmit}>
        <p css={eyebrowStyle}>{GUEST_TRIAL_COPY.inputEyebrow}</p>
        <p css={descriptionStyle}>{GUEST_TRIAL_COPY.inputDescription}</p>

        <label htmlFor={fieldId} css={labelStyle}>
          {GUEST_TRIAL_COPY.inputLabel}
        </label>
        <textarea
          id={fieldId}
          css={textareaStyle}
          value={sourceText}
          placeholder={GUEST_TRIAL_COPY.inputPlaceholder}
          aria-invalid={errorMessage !== null}
          aria-describedby={errorMessage ? errorId : undefined}
          onChange={(event): void => {
            setSourceText(event.target.value);
            setErrorMessage(null);
          }}
        />
        <p css={countStyle}>
          {Array.from(sourceText).length} / {MAX_SOURCE_TEXT_LENGTH}
        </p>
        {errorMessage && (
          <p id={errorId} role="alert" css={errorStyle}>
            {errorMessage}
          </p>
        )}

        <div css={submitAreaStyle}>
          <ActionButton
            type="submit"
            label={
              isSubmitting
                ? GUEST_TRIAL_COPY.submittingAction
                : GUEST_TRIAL_COPY.submitAction
            }
            disabled={isSubmitting}
          />
        </div>
      </form>
    </BottomSheet>
  );
};

export default GuestTrialInputSheet;

const formStyle = css`
  display: flex;
  flex-direction: column;
  padding: 8px 0 calc(20px + env(safe-area-inset-bottom));
`;

const eyebrowStyle = css`
  color: ${theme.colors.foreground.brand};
  font-size: 13px;
  font-weight: 600;
  line-height: 20px;
`;

const descriptionStyle = css`
  margin-top: 4px;
  color: ${theme.colors.foreground.neutralMuted};
  font-size: 14px;
  line-height: 22px;
`;

const labelStyle = css`
  margin-top: 20px;
  color: ${theme.colors.foreground.neutralMuted};
  font-size: 13px;
  line-height: 20px;
`;

const textareaStyle = css`
  width: 100%;
  min-height: 132px;
  margin-top: 8px;
  padding: 14px 16px;
  border: 1px solid ${theme.colors.stroke.outline};
  border-radius: 16px;
  background: ${theme.colors.background.surface};
  color: ${theme.colors.foreground.neutral};
  font: inherit;
  font-size: 16px;
  line-height: 26px;
  resize: none;

  &:focus {
    outline: none;
    border-color: ${theme.colors.background.brandSolid};
  }

  &[aria-invalid='true'] {
    border-color: ${theme.colors.foreground.critical};
  }
`;

const countStyle = css`
  margin-top: 6px;
  color: ${theme.colors.foreground.neutralMuted};
  font-size: 12px;
  text-align: right;
`;

const errorStyle = css`
  margin-top: 4px;
  color: ${theme.colors.foreground.critical};
  font-size: 13px;
  line-height: 20px;
`;

const submitAreaStyle = css`
  margin-top: 24px;
`;
