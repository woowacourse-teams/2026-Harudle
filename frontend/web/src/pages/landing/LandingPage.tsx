import {
  useEffect,
  useRef,
  useState,
  type JSX,
  type ReactNode,
  type RefObject,
} from 'react';
import { css, type SerializedStyles } from '@emotion/react';
import generationStep1Image from '../../assets/images/generation-step-1-reading.png';
import generationStep2Image from '../../assets/images/generation-step-2-writing.png';
import generationStep3Image from '../../assets/images/generation-step-3-selecting-panels.png';
import generationStep4Image from '../../assets/images/generation-step-4-painting.png';
import generationCompleteImage from '../../assets/images/generation-step-5-complete.png';
import { theme } from '../../styles/theme';
import DiaryGenerateStepper from '../diary-generating/DiaryGenerateStepper';
import LandingContent from './LandingContent';
import LandingLoginCta from './LandingLoginCta';
import { getKoreanToday, validateGuestDiary } from './guestDiaryValidation';
import { isGuestTrialAlreadyUsedError } from './guestTrialErrors';
import type { GuestDiaryResponse } from './guestTrialApi';
import useGuestDiaryCreation, {
  type GuestDiaryCreationState,
} from './useGuestDiaryCreation';
import { useAnalytics } from '../../posthog/useAnalytics';
import { GUEST_TRIAL_COPY } from './trialCopy';

interface LandingPageProps {
  entryFeedback?: ReactNode;
}

const LandingPage = ({
  entryFeedback = null,
}: LandingPageProps): JSX.Element => {
  const { track } = useAnalytics();
  const { creationState, submitDiary, retryDiary } = useGuestDiaryCreation({
    enabled: entryFeedback === null,
  });
  const sourceTextRef = useRef<HTMLTextAreaElement>(null);
  const [sourceText, setSourceText] = useState('');
  const [sourceTextError, setSourceTextError] = useState<string | null>(null);

  const handleSubmit = (event: React.FormEvent<HTMLFormElement>): void => {
    event.preventDefault();
    const request = { diaryDate: getKoreanToday(), sourceText };
    const errors = validateGuestDiary(request);

    setSourceTextError(
      errors.sourceText
        ? sourceText.trim()
          ? errors.sourceText
          : GUEST_TRIAL_COPY.emptyInputError
        : null,
    );

    if (errors.sourceText) {
      sourceTextRef.current?.focus({ preventScroll: true });
      return;
    }

    track('landing_trial_diary_create_clicked');
    void submitDiary(request);
  };

  return (
    <LandingContent
      trialActionLabel={
        creationState.status === 'success'
          ? GUEST_TRIAL_COPY.resultAction
          : creationState.status === 'generating'
            ? GUEST_TRIAL_COPY.generatingAction
            : creationState.status === 'error' &&
                isGuestTrialAlreadyUsedError(creationState.error)
              ? GUEST_TRIAL_COPY.usedAction
              : undefined
      }
      trialSection={
        <section
          css={trialFormSectionStyle}
          aria-label="로그인 없이 1회 네컷만화 체험"
        >
          {entryFeedback ?? (
            <LandingTrialCard
              creationState={creationState}
              sourceText={sourceText}
              sourceTextError={sourceTextError}
              sourceTextRef={sourceTextRef}
              onSourceTextChange={(value) => {
                setSourceText(value);
                setSourceTextError(null);
              }}
              onSubmit={handleSubmit}
              onRetry={() => void retryDiary()}
            />
          )}
        </section>
      }
    />
  );
};

export default LandingPage;

const generationSteps = [
  {
    message: GUEST_TRIAL_COPY.generationMessages[0],
    image: generationStep1Image,
  },
  {
    message: GUEST_TRIAL_COPY.generationMessages[1],
    image: generationStep2Image,
  },
  {
    message: GUEST_TRIAL_COPY.generationMessages[2],
    image: generationStep3Image,
  },
  {
    message: GUEST_TRIAL_COPY.generationMessages[3],
    image: generationStep4Image,
  },
] as const;

interface LandingTrialCardProps {
  creationState: GuestDiaryCreationState;
  sourceText: string;
  sourceTextError: string | null;
  sourceTextRef: RefObject<HTMLTextAreaElement | null>;
  onSourceTextChange: (value: string) => void;
  onSubmit: React.FormEventHandler<HTMLFormElement>;
  onRetry: () => void;
}

const LandingTrialCard = ({
  creationState,
  sourceText,
  sourceTextError,
  sourceTextRef,
  onSourceTextChange,
  onSubmit,
  onRetry,
}: LandingTrialCardProps): JSX.Element => {
  const [placeholderIndex, setPlaceholderIndex] = useState(0);
  const [focused, setFocused] = useState(false);
  useEffect(() => {
    const media = window.matchMedia?.('(prefers-reduced-motion: reduce)');
    if (
      creationState.status !== 'writing' ||
      sourceText ||
      focused ||
      media?.matches
    )
      return;
    const timer = window.setInterval((): void => {
      setPlaceholderIndex(
        (current): number =>
          (current + 1) % GUEST_TRIAL_COPY.placeholders.length,
      );
    }, 5000);
    const handleMotionChange = (): void => {
      if (media?.matches) window.clearInterval(timer);
    };
    media?.addEventListener('change', handleMotionChange);
    return (): void => {
      window.clearInterval(timer);
      media?.removeEventListener('change', handleMotionChange);
    };
  }, [creationState.status, sourceText, focused]);

  if (creationState.status === 'generating') {
    return <LandingTrialGeneratingCard />;
  }

  if (creationState.status === 'success') {
    return (
      <LandingTrialResultCard
        key={creationState.data.generation.imageUrl}
        diary={creationState.data}
      />
    );
  }

  if (creationState.status === 'error') {
    return (
      <LandingTrialErrorCard error={creationState.error} onRetry={onRetry} />
    );
  }

  return (
    <form
      css={trialCardStyle}
      aria-labelledby="landing-final-title"
      onSubmit={onSubmit}
    >
      <div css={fieldStyle}>
        <textarea
          ref={sourceTextRef}
          id="guest-diary-source-text"
          aria-label={GUEST_TRIAL_COPY.inputLabel}
          value={sourceText}
          maxLength={300}
          rows={4}
          css={textAreaStyle(sourceTextError !== null)}
          aria-describedby={
            sourceTextError ? 'guest-diary-source-text-error' : undefined
          }
          placeholder={GUEST_TRIAL_COPY.placeholders[placeholderIndex]}
          onFocus={() => setFocused(true)}
          onBlur={() => setFocused(false)}
          aria-invalid={sourceTextError !== null}
          onChange={(event) => onSourceTextChange(event.target.value)}
        />
        <span css={descriptionRowStyle}>
          {sourceTextError && (
            <span
              id="guest-diary-source-text-error"
              role="alert"
              css={errorStyle}
            >
              {sourceTextError}
            </span>
          )}
          <span css={countStyle} data-has-content={sourceText.length > 0}>
            {Array.from(sourceText).length} / 300
          </span>
        </span>
      </div>

      <button type="submit" css={primaryButtonStyle}>
        {GUEST_TRIAL_COPY.createAction}
      </button>
      <p css={hintStyle}>{GUEST_TRIAL_COPY.usageNotice}</p>
    </form>
  );
};

const LandingTrialGeneratingCard = (): JSX.Element => {
  const [stepIndex, setStepIndex] = useState(0);
  const [showExtendedWaitMessage, setShowExtendedWaitMessage] = useState(false);

  useEffect(() => {
    if (stepIndex >= generationSteps.length - 1) {
      const timeoutId = window.setTimeout(() => {
        setShowExtendedWaitMessage(true);
      }, 6_000);

      return () => window.clearTimeout(timeoutId);
    }

    const timeoutId = window.setTimeout(() => {
      setStepIndex((currentStep) => currentStep + 1);
    }, 3_000);

    return () => window.clearTimeout(timeoutId);
  }, [stepIndex]);

  const currentStep = generationSteps[stepIndex];

  return (
    <div
      css={trialCardStyle}
      role="status"
      aria-live="polite"
      aria-labelledby="landing-trial-generating-title"
    >
      <header css={[formHeaderStyle, centeredHeaderStyle]}>
        <p css={formEyebrowStyle}>잠시만 기다려주세요</p>
        <h2 id="landing-trial-generating-title" css={formTitleStyle}>
          {GUEST_TRIAL_COPY.generatingTitle}
        </h2>
      </header>

      <img src={currentStep.image} alt="" css={generationImageStyle} />
      <p css={generationMessageStyle}>{currentStep.message}</p>
      {showExtendedWaitMessage ? (
        <p css={extendedWaitMessageStyle}>{GUEST_TRIAL_COPY.extendedWait}</p>
      ) : null}

      <div css={generationStepperWrapperStyle}>
        <DiaryGenerateStepper loadingStep={stepIndex + 1} />
      </div>

      <p css={freeNoticeStyle}>{GUEST_TRIAL_COPY.generatingNotice}</p>
    </div>
  );
};

const LandingTrialResultCard = ({
  diary,
}: {
  diary: GuestDiaryResponse;
}): JSX.Element => {
  const [imageStatus, setImageStatus] = useState<
    'loading' | 'loaded' | 'error'
  >('loading');

  if (imageStatus === 'loading') {
    return (
      <>
        <img
          src={diary.generation.imageUrl}
          alt=""
          aria-hidden="true"
          data-testid="landing-trial-result-preload"
          css={resultPreloadImageStyle}
          onLoad={() => setImageStatus('loaded')}
          onError={() => setImageStatus('error')}
        />
        <div
          css={trialCardStyle}
          role="status"
          aria-live="polite"
          aria-labelledby="landing-trial-result-loading-title"
        >
          <header css={[formHeaderStyle, centeredHeaderStyle]}>
            <p css={formEyebrowStyle}>거의 다 됐어요</p>
            <h2 id="landing-trial-result-loading-title" css={formTitleStyle}>
              완성한 네컷을 불러오고 있어요
            </h2>
          </header>
          <img
            src={generationCompleteImage}
            alt=""
            css={generationImageStyle}
          />
          <p css={generationMessageStyle}>
            결과 사진이 모두 준비되면 바로 보여드릴게요
          </p>
        </div>
      </>
    );
  }

  return (
    <article css={trialCardStyle} aria-labelledby="landing-trial-result-title">
      <header css={[formHeaderStyle, centeredHeaderStyle]}>
        <p css={formEyebrowStyle}>네컷이 완성됐어요!</p>
        <h2 id="landing-trial-result-title" css={formTitleStyle}>
          {diary.generation.title}
        </h2>
      </header>

      {imageStatus === 'loaded' ? (
        <img
          src={diary.generation.imageUrl}
          alt={`${diary.generation.title} 네컷만화`}
          css={resultImageStyle}
          onError={() => setImageStatus('error')}
        />
      ) : (
        <p role="alert" css={resultImageErrorStyle}>
          결과 이미지를 불러오지 못했어요
        </p>
      )}

      <section css={resultCtaStyle} aria-labelledby="landing-trial-login-title">
        <h3 id="landing-trial-login-title" css={resultCtaTitleStyle}>
          더 만들어보고 싶나요?
        </h3>
        <p css={freeNoticeStyle}>{GUEST_TRIAL_COPY.loginNotice}</p>
        <LandingLoginCta
          label={GUEST_TRIAL_COPY.loginAction}
          analyticsEvent="landing_trial_login_clicked"
          location="result"
        />
      </section>
    </article>
  );
};

const LandingTrialErrorCard = ({
  error,
  onRetry,
}: {
  error: Error;
  onRetry: () => void;
}): JSX.Element => {
  const trialAlreadyUsed = isGuestTrialAlreadyUsedError(error);

  return (
    <div css={trialCardStyle} role="alert">
      <header css={[formHeaderStyle, centeredHeaderStyle]}>
        <h2 css={formTitleStyle}>
          {trialAlreadyUsed
            ? '게스트 체험을 이미 사용했어요'
            : GUEST_TRIAL_COPY.failureTitle}
        </h2>
      </header>

      {!trialAlreadyUsed && <p css={errorMessageStyle}>{error.message}</p>}

      {trialAlreadyUsed ? (
        <>
          <p css={freeNoticeStyle}>{GUEST_TRIAL_COPY.loginNotice}</p>
          <LandingLoginCta
            label={GUEST_TRIAL_COPY.loginAction}
            analyticsEvent="landing_trial_login_clicked"
            location="already_used"
          />
        </>
      ) : (
        <button type="button" css={primaryButtonStyle} onClick={onRetry}>
          같은 내용으로 다시 시도하기
        </button>
      )}
    </div>
  );
};

const trialFormSectionStyle = css`
  width: 100%;
  padding: 0;
  background-color: ${theme.colors.background.brandWeak};
`;

const trialCardStyle = css`
  display: flex;
  flex-direction: column;
  gap: 20px;
  width: 100%;
  padding: 28px 20px 24px;
  border: 1px solid ${theme.colors.stroke.brandWeak};
  border-radius: 24px;
  background-color: ${theme.colors.background.surface};
  box-shadow: 0 18px 40px rgb(47 40 77 / 8%);
`;

const formHeaderStyle = css`
  display: flex;
  flex-direction: column;
  gap: 8px;
  margin-bottom: 4px;
`;

const centeredHeaderStyle = css`
  align-items: center;
  text-align: center;
`;

const formEyebrowStyle = css`
  margin: 0;
  color: ${theme.colors.foreground.brand};
  font-size: 13px;
  font-weight: 700;
  line-height: 20px;
`;

const formTitleStyle = css`
  margin: 0;
  color: ${theme.colors.foreground.neutral};
  font-size: 24px;
  font-weight: 800;
  line-height: 34px;
  letter-spacing: -0.02em;
  word-break: keep-all;
`;

const fieldStyle = css`
  display: flex;
  flex-direction: column;
  gap: 8px;
`;

const textAreaStyle = (hasError: boolean): SerializedStyles => css`
  width: 100%;
  min-height: 136px;
  padding: 16px;
  border: 1px solid
    ${hasError ? theme.colors.stroke.critical : theme.colors.stroke.outline};
  border-radius: 16px;
  outline: none;
  resize: none;
  background-color: ${theme.colors.background.surface};
  color: ${theme.colors.foreground.neutral};
  font-size: 16px;
  line-height: 26px;

  &::placeholder {
    color: ${theme.colors.foreground.placeholder};
  }

  &:focus {
    border-color: ${
      hasError ? theme.colors.stroke.critical : theme.colors.stroke.brandStrong
    };
    background-color: ${theme.colors.background.surface};
    box-shadow: 0 0 0 3px rgb(115 85 218 / 10%);
  }
`;

const descriptionRowStyle = css`
  display: flex;
  justify-content: space-between;
  gap: 12px;
  min-height: 20px;
`;

const errorStyle = css`
  color: ${theme.colors.foreground.critical};
  font-size: 13px;
  line-height: 20px;
`;

const hintStyle = css`
  margin: 0;
  color: ${theme.colors.foreground.neutralMuted};
  font-size: 13px;
  line-height: 20px;
  text-align: center;
  white-space: pre-line;
  word-break: keep-all;
`;

const countStyle = css`
  margin-left: auto;
  flex-shrink: 0;
  color: ${theme.colors.foreground.neutralMuted};
  font-size: 13px;
  line-height: 20px;
  &[data-has-content='true'] {
    color: ${theme.colors.foreground.brand};
  }
`;

const primaryButtonStyle = css`
  display: flex;
  align-items: center;
  justify-content: center;
  width: 100%;
  min-height: 56px;
  padding: 14px 20px;
  border: none;
  border-radius: 16px;
  background-color: ${theme.colors.background.brandStrong};
  color: ${theme.colors.foreground.onBrand};
  font-size: 16px;
  font-weight: 700;
  line-height: 24px;
  cursor: pointer;
  transition: transform 180ms ease;

  &:active {
    transform: scale(0.99);
  }

  &:focus-visible {
    outline: 3px solid ${theme.colors.stroke.focusRing};
    outline-offset: 2px;
  }

  @media (prefers-reduced-motion: reduce) {
    transition-duration: 1ms;
  }
`;

const generationImageStyle = css`
  width: min(100%, 260px);
  margin: -8px auto -20px;
  aspect-ratio: 1;
  object-fit: contain;
`;

const generationMessageStyle = css`
  min-height: 52px;
  margin: 0;
  color: ${theme.colors.foreground.neutral};
  font-size: 18px;
  font-weight: 700;
  line-height: 26px;
  text-align: center;
  word-break: keep-all;
`;

const extendedWaitMessageStyle = css`
  margin: -12px 0 0;
  color: ${theme.colors.foreground.neutralMuted};
  font-size: 14px;
  line-height: 22px;
  text-align: center;
  word-break: keep-all;
`;

const generationStepperWrapperStyle = css`
  display: flex;
  justify-content: center;
  width: 100%;
  overflow-x: auto;
`;

const freeNoticeStyle = css`
  width: 100%;
  margin: 0;
  padding: 12px 14px;
  border-radius: 14px;
  background-color: #f5f1ff;
  color: ${theme.colors.foreground.brand};
  font-size: 14px;
  font-weight: 700;
  line-height: 22px;
  text-align: center;
  word-break: keep-all;
`;

const resultPreloadImageStyle = css`
  position: absolute;
  width: 1px;
  height: 1px;
  opacity: 0;
  pointer-events: none;
`;

const resultImageStyle = css`
  width: 100%;
  aspect-ratio: 1;
  border: 1px solid ${theme.colors.stroke.outline};
  border-radius: 18px;
  background-color: ${theme.colors.background.brandWeak};
  object-fit: cover;
`;

const resultImageErrorStyle = css`
  margin: 0;
  padding: 24px 16px;
  border-radius: 18px;
  background-color: ${theme.colors.background.brandWeak};
  color: ${theme.colors.foreground.neutralMuted};
  font-size: 15px;
  line-height: 24px;
  text-align: center;
`;

const resultCtaStyle = css`
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 16px;
  width: 100%;
  padding-top: 20px;
  border-top: 1px solid ${theme.colors.stroke.divider};
  text-align: center;
`;

const resultCtaTitleStyle = css`
  margin: 0;
  color: ${theme.colors.foreground.neutral};
  font-size: 20px;
  font-weight: 800;
  line-height: 30px;
  word-break: keep-all;
`;

const errorMessageStyle = css`
  margin: 0;
  color: ${theme.colors.foreground.neutralMuted};
  font-size: 15px;
  line-height: 24px;
  text-align: center;
  word-break: keep-all;
`;
