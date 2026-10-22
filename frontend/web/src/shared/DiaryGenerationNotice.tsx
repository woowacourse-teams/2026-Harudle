import { useEffect } from 'react';
import { useNavigate } from 'react-router';
import { getDiaryGenerationStatus } from '../domain/diary/diaryGenerate';
import useDiaryGenerationId from './useDiaryGenerationId';
import { useToastContext } from './ToastContext';
import {
  DIARY_CONTENT_SESSION_KEY,
  DIARY_GENERATION_TOAST_ID_KEY,
} from './constants';
import { removeGenerationId } from '../domain/diary/diaryGenerationStore';
import { DIARY_GENERATION_FAILURE_MESSAGES } from './errorMessage';
import { RequestError } from './api';

const DiaryGenerationNotice = () => {
  const generationId = useDiaryGenerationId();
  const navigate = useNavigate();

  const { addToast, deleteToast, updateToast } = useToastContext();

  useEffect(() => {
    if (!generationId) return;

    const toastId = addToast(
      {
        type: 'loading',
        message: '네컷만화를 만들고 있어요',
        description: '완성되면 알려드릴게요.',
        onClose: () => {
          deleteToast(toastId);
        },
      },
      'infinite',
      generationId,
    );
    localStorage.setItem(DIARY_GENERATION_TOAST_ID_KEY, toastId);

    const execute = async (): Promise<void> => {
      const generationStatus = await getDiaryGenerationStatus({ generationId });
      switch (generationStatus.status) {
        case 'SUCCEEDED': {
          const savedToastId = localStorage.getItem(
            DIARY_GENERATION_TOAST_ID_KEY,
          );
          if (savedToastId) {
            updateToast(savedToastId, {
              type: 'success',
              message: '네컷만화가 완성됐어요!',
              description: '',
              action: {
                label: '보러가기',
                onClick: (): void => {
                  deleteToast(toastId);
                  void navigate(`/diary/${generationStatus.diaryId}`);
                },
              },
            });
          }

          removeGenerationId();
          localStorage.removeItem(DIARY_GENERATION_TOAST_ID_KEY);
          sessionStorage.removeItem(DIARY_CONTENT_SESSION_KEY); // 생성 성공시에만 작성된 내용을 지운다.
          return;
        }
        case 'FAILED': {
          const savedToastId = localStorage.getItem(
            DIARY_GENERATION_TOAST_ID_KEY,
          );
          if (savedToastId) {
            const [message, description] =
              DIARY_GENERATION_FAILURE_MESSAGES[generationStatus.errorCode];
            updateToast(savedToastId, {
              type: 'error',
              message,
              description,
              action: {
                label: '다시 작성하기',
                onClick: (): void => {
                  deleteToast(toastId);
                  void navigate('/diary-write');
                },
              },
            });
          }

          removeGenerationId();
          localStorage.removeItem(DIARY_GENERATION_TOAST_ID_KEY);
          return;
        }
        case 'PROCESSING': {
          return;
        }
        case 'PENDING': {
          return;
        }
        default: {
          const exhaustiveCheck: never = generationStatus;
          return exhaustiveCheck;
        }
      }
    };

    const intervalId = setInterval(() => {
      void execute().catch((error: unknown): void => {
        if (
          error instanceof RequestError &&
          error.problem.code === 'GENERATION_NOT_FOUND'
        ) {
          const savedToastId = localStorage.getItem(
            DIARY_GENERATION_TOAST_ID_KEY,
          );
          if (savedToastId) {
            updateToast(savedToastId, {
              type: 'error',
              message: error.problem.detail,
              description: '다시 시도해주세요',
              action: {
                label: '다시 작성하기',
                onClick: (): void => {
                  deleteToast(toastId);
                  void navigate('/diary-write');
                },
              },
            });
          }
          clearInterval(intervalId);
          removeGenerationId();
          localStorage.removeItem(DIARY_GENERATION_TOAST_ID_KEY);
        }
      });
    }, 5000);

    return () => {
      clearInterval(intervalId);
    };
  }, [generationId, navigate]);

  return null;
};

export default DiaryGenerationNotice;
