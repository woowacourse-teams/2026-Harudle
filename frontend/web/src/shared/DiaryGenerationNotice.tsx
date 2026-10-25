import { useEffect } from 'react';
import { useNavigate } from 'react-router';
import { getDiaryGenerationStatus } from '../domain/diary/diaryGenerate';
import useDiaryGenerationId from './useDiaryGenerationId';
import { useToastContext } from './ToastContext';
import {
  DIARY_CONTENT_SESSION_KEY,
  DIARY_GENERATION_TOAST_ID_KEY,
} from './constants';
import {
  getGenerationId,
  removeGenerationId,
} from '../domain/diary/diaryGenerationStore';
import { DIARY_GENERATION_FAILURE_MESSAGES } from './errorMessage';
import { RequestError } from './api';
import { AuthenticationRequiredError } from './auth';

const DiaryGenerationNotice = () => {
  const generationId = useDiaryGenerationId();
  const navigate = useNavigate();

  const { addToast, deleteToast, updateToast } = useToastContext();

  useEffect(() => {
    if (!generationId) return;

    let active = true;
    let settled = false;
    const toastId = addToast(
      {
        type: 'loading',
        message: '네컷만화를 만들고 있어요',
        description: '완성되면 알려드릴게요.',
        onClose: () => {
          if (localStorage.getItem(DIARY_GENERATION_TOAST_ID_KEY) === toastId) {
            localStorage.removeItem(DIARY_GENERATION_TOAST_ID_KEY);
          }
          deleteToast(toastId);
        },
      },
      'infinite',
      generationId,
    );
    localStorage.setItem(DIARY_GENERATION_TOAST_ID_KEY, toastId);

    const execute = async (): Promise<void> => {
      if (!active) return;
      const generationStatus = await getDiaryGenerationStatus({ generationId });
      if (!active || getGenerationId() !== generationId) return;

      switch (generationStatus.status) {
        case 'SUCCEEDED': {
          settled = true;
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
          } else {
            // 요청은 성공했는데 id가 없다는 것은 사용자가 토스트를 지운 것이기 때문에
            // 새 토스트를 넣어준다.
            addToast(
              {
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
                onClose: () => {
                  deleteToast(toastId);
                },
              },
              'infinite',
              toastId,
            );
          }

          removeGenerationId();
          localStorage.removeItem(DIARY_GENERATION_TOAST_ID_KEY);
          sessionStorage.removeItem(DIARY_CONTENT_SESSION_KEY); // 생성 성공시에만 작성된 내용을 지운다.
          return;
        }
        case 'FAILED': {
          settled = true;
          const savedToastId = localStorage.getItem(
            DIARY_GENERATION_TOAST_ID_KEY,
          );
          const [message, description] =
            DIARY_GENERATION_FAILURE_MESSAGES[generationStatus.errorCode];
          if (savedToastId) {
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
          } else {
            addToast(
              {
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
                onClose: (): void => {
                  deleteToast(toastId);
                },
              },
              'infinite',
              toastId,
            );
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
        if (!active || getGenerationId() !== generationId) return;

        if (
          error instanceof AuthenticationRequiredError ||
          (error instanceof RequestError &&
            (error.problem.code === 'GENERATION_NOT_FOUND' ||
              error.problem.code === 'INVALID_REFRESH_TOKEN'))
        ) {
          settled = true;
          const savedToastId = localStorage.getItem(
            DIARY_GENERATION_TOAST_ID_KEY,
          );
          if (savedToastId) {
            updateToast(savedToastId, {
              type: 'error',
              message: error.message,
              description: '다시 시도해주세요',
              action: {
                label: '다시 작성하기',
                onClick: (): void => {
                  deleteToast(toastId);
                  void navigate('/diary-write');
                },
              },
            });
          } else {
            addToast(
              {
                type: 'error',
                message: error.message,
                description: '다시 시도해주세요',
                action: {
                  label: '다시 작성하기',
                  onClick: (): void => {
                    deleteToast(toastId);
                    void navigate('/diary-write');
                  },
                },
                onClose: (): void => {
                  deleteToast(toastId);
                },
              },
              'infinite',
              toastId,
            );
          }
          clearInterval(intervalId);
          removeGenerationId();
          localStorage.removeItem(DIARY_GENERATION_TOAST_ID_KEY);
        }
      });
    }, 5000);

    return () => {
      active = false;
      clearInterval(intervalId);
      if (!settled) {
        deleteToast(toastId);
        if (localStorage.getItem(DIARY_GENERATION_TOAST_ID_KEY) === toastId) {
          localStorage.removeItem(DIARY_GENERATION_TOAST_ID_KEY);
        }
      }
    };
  }, [generationId, navigate]);

  return null;
};

export default DiaryGenerationNotice;
