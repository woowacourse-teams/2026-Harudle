import { useEffect, useState } from 'react';
import DiaryGenerateStepper from './DiaryGenerateStepper';
import PageHeader from '../../shared/PageHeader';
import { API_BASE_URL, type ApiRequestStatus } from '../../shared/api';
import { useLocation, useNavigate } from 'react-router';

const DiaryGeneratingPage = () => {
  const [loadingStep, setLoadingStep] = useState<number>(1);
  const [diaryGenerateRequest, setDiaryGenerateRequest] =
    useState<ApiRequestStatus>('idle');
  const navigate = useNavigate();
  const { state } = useLocation();

  useEffect(() => {
    const postDiaryGenerate = async (): Promise<void> => {
      setDiaryGenerateRequest('loading');

      try {
        const response = await fetch(`${API_BASE_URL}/diaries`, {
          method: 'POST',
          headers: {
            'Content-Type': 'application/json',
          },
          body: JSON.stringify(state),
        });

        if (!response.ok) {
          throw new Error('일기 생성에 실패했습니다.');
        }

        setDiaryGenerateRequest('success');
      } catch (error: unknown) {
        setDiaryGenerateRequest('error');

        if (error instanceof Error) {
          alert(error.message);
          navigate(-1);
        }
      }
    };

    void postDiaryGenerate();
  }, [state, navigate]);
  useEffect(() => {
    if (loadingStep >= 4) {
      return;
    }

    const timeoutId = setTimeout(() => {
      setLoadingStep((previousStep) => previousStep + 1);
    }, 2_000);

    return () => clearTimeout(timeoutId);
  }, [loadingStep]);

  const isGenerationComplete =
    loadingStep === 4 && diaryGenerateRequest === 'success';

  const displayedStep = isGenerationComplete ? 5 : loadingStep;

  useEffect(() => {
    if (!isGenerationComplete) {
      return;
    }

    const timeoutId = setTimeout(() => {
      navigate('/');
    }, 1_000);

    return () => clearTimeout(timeoutId);
  }, [isGenerationComplete, navigate]);

  return (
    <div>
      <PageHeader
        leftButton={<button>뒤로가기</button>}
        title={null}
        rightButton={null}
      />
      {displayedStep === 1 && <div>오늘의 이야기를 차근차근 읽고 있어요</div>}

      {displayedStep === 2 && (
        <div>기억에 남는 장면을 한 장면씩 적어보고 있어요</div>
      )}
      {displayedStep === 3 && (
        <div>네 장면을 고르고 이야기의 흐름을 맞추고 있어요</div>
      )}
      {displayedStep === 4 && (
        <div>색을 더하고 다듬어 네컷 만화를 완성하고 있어요</div>
      )}
      {displayedStep === 5 && <div>완성했어요! 1초 뒤에 앨범으로 이동해요</div>}

      <DiaryGenerateStepper loadingStep={displayedStep} />
    </div>
  );
};

export default DiaryGeneratingPage;
