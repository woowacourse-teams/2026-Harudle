import { useEffect, useState } from 'react';
import { css } from '@emotion/react';
import DiaryGenerateStepper from './DiaryGenerateStepper';
import PageHeader from '../../shared/PageHeader';
import { API_BASE_URL, type ApiRequestStatus } from '../../shared/api';
import { useLocation, useNavigate } from 'react-router';
import { theme } from '../../styles/theme';
import backIcon from '../../assets/icons/back.svg';
import generationStep1Image from '../../assets/images/generation-step-1-reading.png';
import generationStep2Image from '../../assets/images/generation-step-2-writing.png';
import generationStep3Image from '../../assets/images/generation-step-3-selecting-panels.png';
import generationStep4Image from '../../assets/images/generation-step-4-painting.png';
import generationCompleteImage from '../../assets/images/generation-step-5-complete.png';
import { authFetch } from '../../shared/auth';
import ActionButton from '../../shared/ActionButton';

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
    message: '완성했어요! 1초 뒤에 앨범으로 이동해요',
    image: generationCompleteImage,
  },
] as const;

const isRecord = (value: unknown): value is Record<string, unknown> => {
  return typeof value === 'object' && value !== null;
};

const DiaryGeneratingPage = () => {
  const [loadingStep, setLoadingStep] = useState<number>(1);
  const [diaryGenerateRequest, setDiaryGenerateRequest] =
    useState<ApiRequestStatus>('idle');
  const [createdDiaryId, setCreatedDiaryId] = useState<string | null>(null);
  const navigate = useNavigate();
  const { state } = useLocation();

  useEffect(() => {
    const postDiaryGenerate = async (): Promise<void> => {
      setDiaryGenerateRequest('loading');

      try {
        const response = await authFetch(`${API_BASE_URL}/diaries`, {
          method: 'POST',
          headers: {
            'Content-Type': 'application/json',
            'Idempotency-Key': state.idempotencyKey,
          },
          body: JSON.stringify({
            diaryDate: state.diaryDate,
            sourceText: state.sourceText,
          }),
        });

        if (!response.ok) {
          throw new Error('일기 생성에 실패했습니다.');
        }

        const data: unknown = await response.json();

        if (!isRecord(data) || typeof data.id !== 'string') {
          throw new Error('일기 생성 응답 형식이 일치하지 않습니다.');
        }

        setCreatedDiaryId(data.id);
        setDiaryGenerateRequest('success');
      } catch {
        setDiaryGenerateRequest('error');
      }
    };

    void postDiaryGenerate();
  }, [state, navigate]);
  useEffect(() => {
    if (loadingStep >= 4 || diaryGenerateRequest === 'error') {
      return;
    }

    const timeoutId = setTimeout(() => {
      setLoadingStep((previousStep) => previousStep + 1);
    }, 2_000);

    return () => clearTimeout(timeoutId);
  }, [diaryGenerateRequest, loadingStep]);

  const isGenerationComplete =
    loadingStep === 4 &&
    diaryGenerateRequest === 'success' &&
    createdDiaryId !== null;

  const displayedStep = isGenerationComplete ? 5 : loadingStep;
  const currentStep = generationSteps[displayedStep - 1];
  const isCompleteStep = displayedStep === 5;

  useEffect(() => {
    if (!isGenerationComplete || createdDiaryId === null) {
      return;
    }

    const timeoutId = setTimeout(() => {
      navigate(`/diaries/${createdDiaryId}`, { replace: true });
    }, 1_000);

    return () => clearTimeout(timeoutId);
  }, [createdDiaryId, isGenerationComplete, navigate]);

  return (
    <div css={pageStyle}>
      <PageHeader
        leftButton={
          <button css={backButtonStyle} onClick={() => navigate(-1)}>
            <img css={backIconStyle} src={backIcon} alt="뒤로가기" />
          </button>
        }
        title={null}
        rightButton={null}
      />

      <main css={contentStyle}>
        {diaryGenerateRequest === 'error' ? (
          <div css={errorStyle}>
            <div css={errorMessageStyle}>일기 생성에 실패했습니다.</div>
            <ActionButton
              label="다시 작성하기"
              onClick={() =>
                navigate('/diary-write', {
                  replace: true,
                  state,
                })
              }
            />
          </div>
        ) : (
          <>
            <img
              css={illustrationStyle(isCompleteStep)}
              src={currentStep.image}
              alt="일기 생성 중"
            />
            <div css={messageStyle(isCompleteStep)}>{currentStep.message}</div>
            <DiaryGenerateStepper loadingStep={displayedStep} />
          </>
        )}
      </main>
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
  padding: 12px 20px 10px;
  overflow: hidden;
  background-color: ${theme.colors.background};
  box-sizing: border-box;
`;

const backButtonStyle = css`
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

const backIconStyle = css`
  width: 24px;
  height: 24px;
`;

const contentStyle = css`
  display: flex;
  flex: 1;
  flex-direction: column;
  align-items: center;
  width: 100%;
`;

const errorStyle = css`
  display: flex;
  flex: 1;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 20px;
  width: 342px;
`;

const errorMessageStyle = css`
  color: ${theme.colors.textPrimary};
  font-size: 18px;
  font-weight: 700;
  line-height: 28px;
`;

const illustrationStyle = (isCompleteStep: boolean) => css`
  width: ${isCompleteStep ? '269px' : '220px'};
  height: ${isCompleteStep ? '272px' : '220px'};
  margin-top: -2px;
  object-fit: cover;
`;

const messageStyle = (isCompleteStep: boolean) => css`
  width: 300px;
  min-height: 68px;
  margin-top: ${isCompleteStep ? '-21px' : '12px'};
  color: ${theme.colors.textPrimary};
  font-family: 'Noto Sans KR', sans-serif;
  font-size: 22px;
  font-weight: 700;
  line-height: 34px;
  text-align: center;
  word-break: keep-all;
`;
