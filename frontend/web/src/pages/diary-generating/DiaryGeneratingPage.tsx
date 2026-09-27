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
  const { state: diaryGenerateRequestBody } = useLocation();

  if (!isDiaryGenerateRequest(diaryGenerateRequestBody)) {
    alert('일기 생성 형식이 올바르지 않습니다.');
    return <Navigate to="/" replace />;
  }

  return <DiaryGeneratingContent {...diaryGenerateRequestBody} />;
};

export default DiaryGeneratingPage;

const generationSteps = [
  {
    message: '오늘의 이야기를 차근차근 읽고 있어요',
    image: generationStep1Image,
  },
  {
    message: '기억에 남는 장면을 한 장면씩 적어보고 있어요',
    image: generationStep2Image,
  },
  {
    message: '네 장면을 고르고 이야기의 흐름을 맞추고 있어요',
    image: generationStep3Image,
  },
  {
    message: '색을 더하고 다듬어 네컷 만화를 완성하고 있어요',
    image: generationStep4Image,
  },
  {
    message: '완성했어요! 2초 뒤에 앨범으로 이동해요',
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

  const handleReturnHome = useCallback(() => {
    resetRequest();
    navigate('/', { replace: true });
  }, [resetRequest, navigate]);

  const handleDairyWriteRetry = useCallback(() => {
    resetRequest();
    navigate('/diary-write');
  }, [resetRequest, navigate]);

  if (request.status === 'error') {
    return (
      <DiaryGeneratingError
        error={request.error}
        onReturnHome={handleReturnHome}
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
            onClick={() => navigate('/')}
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
            다른 화면으로 이동해도 일기는 계속 만들어요!
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
