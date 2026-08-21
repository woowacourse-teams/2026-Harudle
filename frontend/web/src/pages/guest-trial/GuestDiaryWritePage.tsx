import { useEffect, useRef, useState } from 'react';
import { css } from '@emotion/react';
import generationStep1Image from '../../assets/images/generation-step-1-reading.png';
import generationStep2Image from '../../assets/images/generation-step-2-writing.png';
import generationStep3Image from '../../assets/images/generation-step-3-selecting-panels.png';
import generationStep4Image from '../../assets/images/generation-step-4-painting.png';
import generationCompleteImage from '../../assets/images/generation-step-5-complete.png';
import { theme } from '../../styles/theme';
import DiaryGenerateStepper from '../diary-generating/DiaryGenerateStepper';
import LandingPage from '../landing/LandingPage';
import GuestLoginCta from './GuestLoginCta';
import { getKoreanToday, validateGuestDiary } from './guestDiaryValidation';
import { isGuestTrialAlreadyUsedError } from './guestTrialErrors';
import type { GuestDiaryResponse } from './guestTrialApi';
import useGuestDiaryCreation, {
  type GuestDiaryCreationState,
} from './useGuestDiaryCreation';

const GuestDiaryWritePage = () => {
  const trialCardRef = useRef<HTMLElement>(null);
  const { creationState, submitDiary, retryDiary } = useGuestDiaryCreation({
    enabled: true,
  });
  const [sourceText, setSourceText] = useState('');
  const [sourceTextError, setSourceTextError] = useState<string | null>(null);

  const handleSubmit = (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    const request = { diaryDate: getKoreanToday(), sourceText };
    const errors = validateGuestDiary(request);

    setSourceTextError(errors.sourceText ?? null);

    if (errors.sourceText) {
      return;
    }

    void submitDiary(request);
  };

  return (
    <LandingPage
      heroAction={
        <button
          type="button"
          css={trialStartButtonStyle}
          onClick={() => {
            trialCardRef.current?.scrollIntoView({
              behavior: 'smooth',
              block: 'start',
            });
          }}
        >
          무료로 사용해보기
        </button>
      }
      finalAction={null}
      trialSection={
        <section
          ref={trialCardRef}
          css={trialFormSectionStyle}
          aria-label="무료 네컷 체험"
        >
          <GuestTrialCard
            creationState={creationState}
            sourceText={sourceText}
            sourceTextError={sourceTextError}
            onSourceTextChange={(value) => {
              setSourceText(value);
              setSourceTextError(null);
            }}
            onSubmit={handleSubmit}
            onRetry={() => void retryDiary()}
          />
        </section>
      }
    />
  );
};

export default GuestDiaryWritePage;

const generationSteps = [
  {
    message: '오늘의 이야기를 차근차근 읽고 있어요',
    image: generationStep1Image,
  },
  {
    message: '기억에 남는 장면을 한 장면씩 적고 있어요',
    image: generationStep2Image,
  },
  {
    message: '네 장면을 고르고 이야기의 흐름을 맞추고 있어요',
    image: generationStep3Image,
  },
  {
    message: '색을 더하고 다듬어 네컷 그림을 완성하고 있어요',
    image: generationStep4Image,
  },
] as const;

interface GuestTrialCardProps {
  creationState: GuestDiaryCreationState;
  sourceText: string;
  sourceTextError: string | null;
  onSourceTextChange: (value: string) => void;
  onSubmit: React.FormEventHandler<HTMLFormElement>;
  onRetry: () => void;
}

const GuestTrialCard = ({
  creationState,
  sourceText,
  sourceTextError,
  onSourceTextChange,
  onSubmit,
  onRetry,
}: GuestTrialCardProps) => {
  if (creationState.status === 'generating') {
    return <GuestTrialGeneratingCard />;
  }

  if (creationState.status === 'success') {
    return (
      <GuestTrialResultCard
        key={creationState.data.generation.imageUrl}
        diary={creationState.data}
      />
    );
  }

  if (creationState.status === 'error') {
    return (
      <GuestTrialErrorCard error={creationState.error} onRetry={onRetry} />
    );
  }

  return (
    <form
      css={trialCardStyle}
      aria-labelledby="guest-trial-form-title"
      onSubmit={onSubmit}
    >
      <header css={formHeaderStyle}>
        <p css={formEyebrowStyle}>로그인 없이 체험해보세요! 전부 무료예요</p>
        <h2 id="guest-trial-form-title" css={formTitleStyle}>
          아무 이야기나 적어주세요
        </h2>
      </header>

      <div css={fieldStyle}>
        <textarea
          id="guest-diary-source-text"
          aria-label="이야기"
          value={sourceText}
          maxLength={300}
          css={textAreaStyle(sourceTextError !== null)}
          aria-describedby={
            sourceTextError
              ? 'guest-diary-source-text-error'
              : 'guest-diary-source-text-hint'
          }
          placeholder="상쾌하게 일어나고 보니 오전 11시였다 부랴부랴 짐을 싸고 버스에서 내리니 비가 와서 비에 홀딱 젖었다"
          onChange={(event) => onSourceTextChange(event.target.value)}
        />
        <span css={descriptionRowStyle}>
          <span
            id={
              sourceTextError
                ? 'guest-diary-source-text-error'
                : 'guest-diary-source-text-hint'
            }
            css={sourceTextError ? errorStyle : hintStyle}
          >
            {sourceTextError ?? '10자 이상 300자 이하로 적어주세요'}
          </span>
          <span css={countStyle}>{Array.from(sourceText).length} / 300</span>
        </span>
      </div>

      <button type="submit" css={primaryButtonStyle}>
        네컷 그림 만들기
      </button>
    </form>
  );
};

const GuestTrialGeneratingCard = () => {
  const [stepIndex, setStepIndex] = useState(0);

  useEffect(() => {
    if (stepIndex >= generationSteps.length - 1) {
      return;
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
      aria-labelledby="guest-trial-generating-title"
    >
      <header css={[formHeaderStyle, centeredHeaderStyle]}>
        <p css={formEyebrowStyle}>잠시만 기다려주세요</p>
        <h2 id="guest-trial-generating-title" css={formTitleStyle}>
          네컷 그림을 만들고 있어요
        </h2>
      </header>

      <img src={currentStep.image} alt="" css={generationImageStyle} />
      <p css={generationMessageStyle}>{currentStep.message}</p>

      <div css={generationStepperWrapperStyle}>
        <DiaryGenerateStepper loadingStep={stepIndex + 1} />
      </div>

      <p css={freeNoticeStyle}>체험도, 로그인 후 이용도 전부 무료예요</p>
    </div>
  );
};

const GuestTrialResultCard = ({ diary }: { diary: GuestDiaryResponse }) => {
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
          data-testid="guest-trial-result-preload"
          css={resultPreloadImageStyle}
          onLoad={() => setImageStatus('loaded')}
          onError={() => setImageStatus('error')}
        />
        <div
          css={trialCardStyle}
          role="status"
          aria-live="polite"
          aria-labelledby="guest-trial-result-loading-title"
        >
          <header css={[formHeaderStyle, centeredHeaderStyle]}>
            <p css={formEyebrowStyle}>거의 다 됐어요</p>
            <h2 id="guest-trial-result-loading-title" css={formTitleStyle}>
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
    <article css={trialCardStyle} aria-labelledby="guest-trial-result-title">
      <header css={[formHeaderStyle, centeredHeaderStyle]}>
        <p css={formEyebrowStyle}>네컷이 완성됐어요!</p>
        <h2 id="guest-trial-result-title" css={formTitleStyle}>
          {diary.generation.title}
        </h2>
      </header>

      {imageStatus === 'loaded' ? (
        <img
          src={diary.generation.imageUrl}
          alt={`${diary.generation.title} 네컷 그림`}
          css={resultImageStyle}
          onError={() => setImageStatus('error')}
        />
      ) : (
        <p role="alert" css={resultImageErrorStyle}>
          결과 이미지를 불러오지 못했어요
        </p>
      )}

      <section css={resultCtaStyle} aria-labelledby="guest-trial-login-title">
        <h3 id="guest-trial-login-title" css={resultCtaTitleStyle}>
          더 만들어보고 싶나요?
        </h3>
        <p css={freeNoticeStyle}>
          로그인 후에도 네컷 그림 만들기는 전부 무료예요
        </p>
        <GuestLoginCta label="로그인하고 무료로 더 만들기" />
      </section>
    </article>
  );
};

const GuestTrialErrorCard = ({
  error,
  onRetry,
}: {
  error: Error;
  onRetry: () => void;
}) => {
  const trialAlreadyUsed = isGuestTrialAlreadyUsedError(error);

  return (
    <div css={trialCardStyle} role="alert">
      <header css={[formHeaderStyle, centeredHeaderStyle]}>
        <h2 css={formTitleStyle}>
          {trialAlreadyUsed
            ? '게스트 체험을 이미 사용했어요'
            : '네컷을 완성하지 못했어요'}
        </h2>
      </header>

      {!trialAlreadyUsed && <p css={errorMessageStyle}>{error.message}</p>}

      {trialAlreadyUsed ? (
        <>
          <p css={freeNoticeStyle}>
            로그인 후에도 네컷 그림 만들기는 전부 무료예요
          </p>
          <GuestLoginCta label="로그인하고 무료로 더 만들기" />
        </>
      ) : (
        <button type="button" css={primaryButtonStyle} onClick={onRetry}>
          같은 내용으로 다시 시도하기
        </button>
      )}
    </div>
  );
};

const trialStartButtonStyle = css`
  display: flex;
  align-items: center;
  justify-content: center;
  width: 100%;
  min-height: 56px;
  padding: 14px 20px;
  border: none;
  border-radius: 16px;
  background-color: ${theme.colors.bg.brand};
  color: #ffffff;
  font-size: 16px;
  font-weight: 700;
  line-height: 24px;
  cursor: pointer;
  transition: transform 180ms ease;

  &:active {
    transform: scale(0.99);
  }

  &:focus-visible {
    outline: 3px solid rgb(115 85 218 / 35%);
    outline-offset: 2px;
  }

  @media (prefers-reduced-motion: reduce) {
    transition-duration: 1ms;
  }
`;

const trialFormSectionStyle = css`
  width: 100%;
  padding: 0 20px 64px;
  background-color: #f8f6ff;
`;

const trialCardStyle = css`
  display: flex;
  flex-direction: column;
  gap: 20px;
  width: 100%;
  padding: 28px 20px 24px;
  border: 1px solid #efebfa;
  border-radius: 24px;
  background-color: #ffffff;
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
  color: ${theme.colors.text.brand};
  font-size: 13px;
  font-weight: 700;
  line-height: 20px;
`;

const formTitleStyle = css`
  margin: 0;
  color: ${theme.colors.text.primary};
  font-size: 24px;
  font-weight: 800;
  line-height: 34px;
  letter-spacing: -0.5px;
  word-break: keep-all;
`;

const fieldStyle = css`
  display: flex;
  flex-direction: column;
  gap: 8px;
`;

const textAreaStyle = (hasError: boolean) => css`
  width: 100%;
  min-height: 240px;
  padding: 16px;
  border: 1px solid
    ${hasError ? theme.colors.text.danger : theme.colors.border.primary};
  border-radius: 16px;
  outline: none;
  resize: vertical;
  background-color: #ffffff;
  color: ${theme.colors.text.primary};
  font-size: 16px;
  line-height: 26px;

  &::placeholder {
    color: #8b8793;
  }

  &:focus {
    border-color: ${
      hasError ? theme.colors.text.danger : theme.colors.text.brand
    };
    background-color: #ffffff;
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
  color: ${theme.colors.text.danger};
  font-size: 13px;
  line-height: 20px;
`;

const hintStyle = css`
  color: ${theme.colors.text.secondary};
  font-size: 13px;
  line-height: 20px;
`;

const countStyle = css`
  flex-shrink: 0;
  color: ${theme.colors.text.secondary};
  font-size: 13px;
  line-height: 20px;
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
  background-color: ${theme.colors.bg.brand};
  color: #ffffff;
  font-size: 16px;
  font-weight: 700;
  line-height: 24px;
  cursor: pointer;
  transition: transform 180ms ease;

  &:active {
    transform: scale(0.99);
  }

  &:focus-visible {
    outline: 3px solid rgb(115 85 218 / 35%);
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
  color: ${theme.colors.text.primary};
  font-size: 18px;
  font-weight: 700;
  line-height: 26px;
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
  color: ${theme.colors.text.brand};
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
  border: 1px solid ${theme.colors.border.primary};
  border-radius: 18px;
  background-color: #f8f6ff;
  object-fit: cover;
`;

const resultImageErrorStyle = css`
  margin: 0;
  padding: 24px 16px;
  border-radius: 18px;
  background-color: #f8f6ff;
  color: ${theme.colors.text.secondary};
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
  border-top: 1px solid ${theme.colors.border.primary};
  text-align: center;
`;

const resultCtaTitleStyle = css`
  margin: 0;
  color: ${theme.colors.text.primary};
  font-size: 20px;
  font-weight: 800;
  line-height: 30px;
  word-break: keep-all;
`;

const errorMessageStyle = css`
  margin: 0;
  color: ${theme.colors.text.secondary};
  font-size: 15px;
  line-height: 24px;
  text-align: center;
  word-break: keep-all;
`;
