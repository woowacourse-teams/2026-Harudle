import { useEffect, useState } from 'react';
import DiaryGenerateStepper from './DiaryGenerateStepper';
import { useNavigate } from 'react-router';
import useDiaryGenerate from './useDiaryGenerate';
import generationStep1Image from '../../assets/images/generation-step-1-reading.png';
import generationStep2Image from '../../assets/images/generation-step-2-writing.png';
import generationStep3Image from '../../assets/images/generation-step-3-selecting-panels.png';
import generationStep4Image from '../../assets/images/generation-step-4-painting.png';
import generationCompleteImage from '../../assets/images/generation-step-5-complete.png';
import { css } from '@emotion/react';
import { theme } from '../../styles/theme';

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

export const FINAL_STEP = 5;

const DiaryGeneratingPage = () => {
  const [loadingStep, setLoadingStep] = useState<number>(1);
  const navigate = useNavigate();
  const { diaryGenerateRequest } = useDiaryGenerate();

  useEffect(() => {
    if (loadingStep >= FINAL_STEP - 1) {
      return;
    }

    const timeoutId = setTimeout(() => {
      setLoadingStep((previousStep) => previousStep + 1);
    }, 3_000);

    return () => clearTimeout(timeoutId);
  }, [loadingStep]);

  const isGenerationComplete = diaryGenerateRequest.status === 'success';

  useEffect(() => {
    if (!isGenerationComplete) {
      return;
    }

    const timeoutId = setTimeout(() => {
      navigate(`/diary/${diaryGenerateRequest.data.id}`);
    }, 2_000);

    return () => clearTimeout(timeoutId);
  }, [isGenerationComplete, navigate]);

  const displayedStep = isGenerationComplete ? FINAL_STEP : loadingStep;

  if (diaryGenerateRequest.status === 'error') {
    return <div>{diaryGenerateRequest.error.message}</div>;
  }

  return (
    <div css={pageStyle}>
      <img
        css={illustrationStyle}
        src={generationSteps[displayedStep - 1].image}
      />
      <p css={messageStyle}>{generationSteps[displayedStep - 1].message}</p>

      <DiaryGenerateStepper loadingStep={displayedStep} />
    </div>
  );
};

export default DiaryGeneratingPage;

const pageStyle = css`
  display: flex;
  flex-direction: column;
  align-items: center;
  width: 100%;
  height: 100%;
  overflow-y: auto;
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
  color: ${theme.colors.text.primary};
  font-size: 22px;
  font-weight: 700;
  line-height: 34px;
  text-align: center;
  word-break: keep-all;
`;
