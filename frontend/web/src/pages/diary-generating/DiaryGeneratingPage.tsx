import { DIARY_GENERATING_COPY } from './copy';
import { useCallback, useEffect } from 'react';
import DiaryGenerateStepper from './DiaryGenerateStepper';
import { Navigate, useLocation, useNavigate } from 'react-router';
import generationStep1Image from '../../assets/images/generation-step-1-reading.png';
import generationStep2Image from '../../assets/images/generation-step-2-writing.png';
import generationStep3Image from '../../assets/images/generation-step-3-selecting-panels.png';
import generationStep4Image from '../../assets/images/generation-step-4-painting.png';
import generationCompleteImage from '../../assets/images/generation-step-5-complete.png';
import { css } from '@emotion/react';
import { theme } from '../../styles/theme';
import DiaryGeneratingError from './DiaryGeneratingError';
import { useDiaryGenerateContext } from './DiaryGenerateContext';
import PageHeader from '../../shared/PageHeader';
import backIcon from '../../assets/icons/back.svg';
import {
  isDiaryGenerateRequest,
  type DiaryGenerateRequest,
} from '../../domain/diary/diaryGenerate';
import useDiaryGenerationProgress from './useDiaryGenerationProgress';

const DiaryGeneratingPage = () => {
  const diaryGenerateRequestBody: unknown = useLocation().state;

  if (!isDiaryGenerateRequest(diaryGenerateRequestBody)) {
    alert(DIARY_GENERATING_COPY.invalidRequest);
    return <Navigate to="/album" replace />;
  }

  return <DiaryGeneratingContent {...diaryGenerateRequestBody} />;
};

export default DiaryGeneratingPage;

const generationSteps = [
  {
    message: DIARY_GENERATING_COPY.steps[0],
    image: generationStep1Image,
  },
  {
    message: DIARY_GENERATING_COPY.steps[1],
    image: generationStep2Image,
  },
  {
    message: DIARY_GENERATING_COPY.steps[2],
    image: generationStep3Image,
  },
  {
    message: DIARY_GENERATING_COPY.steps[3],
    image: generationStep4Image,
  },
  {
    message: DIARY_GENERATING_COPY.steps[4],
    image: generationCompleteImage,
  },
] as const;

const DiaryGeneratingContent = (generateRequestBody: DiaryGenerateRequest) => {
  const { execute, request, resetRequest } = useDiaryGenerateContext();
  const { isGenerationComplete, displayedStep } =
    useDiaryGenerationProgress(request);
  const navigate = useNavigate();

  useEffect(() => {
    void execute(generateRequestBody);
  }, [execute, generateRequestBody]);

  const handleReturnAlbum = useCallback(() => {
    resetRequest();
    navigate('/album', { replace: true });
  }, [resetRequest, navigate]);

  const handleDairyWriteRetry = useCallback(() => {
    resetRequest();
    navigate('/diary-write');
  }, [resetRequest, navigate]);

  if (request.status === 'error') {
    return (
      <DiaryGeneratingError
        error={request.error}
        onReturnAlbum={handleReturnAlbum}
        onDiaryWriteRetry={handleDairyWriteRetry}
      />
    );
  }

  return (
    <div css={pageStyle}>
      <PageHeader
        left={
          <button
            type="button"
            aria-label="뒤로 가기"
            css={headerButtonStyle}
            onClick={() => navigate('/album')}
          >
            <img
              src={backIcon}
              alt="뒤로가기 아이콘"
              css={headerButtonIconStyle}
            />
          </button>
        }
        title={null}
        right={null}
      />
      <img
        css={illustrationStyle}
        src={generationSteps[displayedStep - 1].image}
      />
      <p css={messageStyle}>{generationSteps[displayedStep - 1].message}</p>

      <div css={supportingMessageSlotStyle}>
        {!isGenerationComplete && (
          <p css={supportingMessageStyle}>
            {DIARY_GENERATING_COPY.backgroundMessage}
          </p>
        )}
      </div>

      <DiaryGenerateStepper loadingStep={displayedStep} />
    </div>
  );
};

const pageStyle = css`
  display: flex;
  flex-direction: column;
  align-items: center;
  width: 100%;
  height: 100%;
  padding: 24px 20px;
  overflow-y: auto;
`;

const headerButtonStyle = css`
  display: flex;
  align-items: center;
  justify-content: center;
  width: 32px;
  height: 32px;
  padding: 0;
  border: none;
  background-color: transparent;
  cursor: pointer;
`;

const headerButtonIconStyle = css`
  width: 24px;
  height: 24px;
`;

const illustrationStyle = css`
  width: 320px;
  aspect-ratio: 1;
  object-fit: contain;
`;

const messageStyle = css`
  width: 300px;
  min-height: 68px;
  margin-top: -40px;
  color: ${theme.colors.foreground.neutral};
  font-size: 22px;
  font-weight: 700;
  line-height: 34px;
  text-align: center;
  word-break: keep-all;
`;

const supportingMessageSlotStyle = css`
  min-height: 42px;
  margin: 4px 0 16px;
`;

const supportingMessageStyle = css`
  display: flex;
  justify-content: center;
  align-items: center;
  padding: 4px 20px;
  border-radius: 12px;
  background-color: ${theme.colors.background.brandWeak};
  color: ${theme.colors.foreground.brand};
  font-size: 14px;
  font-weight: 600;
  line-height: 22px;
  word-break: keep-all;
`;
