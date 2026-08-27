import { useEffect, useState } from 'react';
import { useNavigate } from 'react-router';
import type { ApiRequest } from '../../shared/api';
import type { DiaryGenerateResponse } from './DiaryGenerateContext';

export const FINAL_STEP = 5;

const useGenerateLoading = (
  diaryGenerateRequest: ApiRequest<DiaryGenerateResponse>,
) => {
  const [loadingStep, setLoadingStep] = useState<number>(1);
  const navigate = useNavigate();
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
      navigate(`/diary/${diaryGenerateRequest.data.id}`, {
        replace: true,
      });
    }, 2_000);

    return () => clearTimeout(timeoutId);
  }, [isGenerationComplete, navigate]);

  const displayedStep = isGenerationComplete ? FINAL_STEP : loadingStep;

  return { isGenerationComplete, displayedStep };
};

export default useGenerateLoading;
