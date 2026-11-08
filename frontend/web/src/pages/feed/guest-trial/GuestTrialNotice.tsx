import { useEffect, useEffectEvent } from 'react';
import { useNavigate } from 'react-router';
import { useToastContext } from '../../../shared/ToastContext';
import type { ToastProps } from '../../../shared/Toast';
import { DIARY_GENERATION_FAILURE_MESSAGES } from '../../../shared/errorMessage';
import { RequestError } from '../../../shared/api';
import type { GenerationFailureCode } from '../../../domain/diary/diaryGeneration';
import type { DiaryGenerationStatusResponse } from '../../../domain/diary/diaryGenerate';
import { getGuestTrialGenerationStatus } from './guestTrialApi';
import {
  hasCompletedOAuth,
  markGuestTrialEndedWithoutResult,
  removeGuestTrialGenerationId,
  requestGuestTrialResultOpen,
  requestGuestTrialStatusCheck,
  saveGuestTrialDiaryId,
  useGuestTrialGenerationId,
  useGuestTrialStatusCheckRequest,
} from './guestTrialStorage';
import { GUEST_TRIAL_COPY } from './copy';

const GENERATION_TOAST_ID = 'guest-trial-generation';

const POLL_INTERVAL_MS = 5_000;

type GuestTrialGenerationOutcome =
  | { readonly type: 'ready'; readonly diaryId: string }
  | { readonly type: 'failed'; readonly errorCode: GenerationFailureCode }
  | { readonly type: 'lost' }
  | { readonly type: 'checkFailed' };

const wait = (ms: number): Promise<void> => {
  return new Promise((resolve): void => {
    setTimeout(resolve, ms);
  });
};

/**
 * 생성이 끝날 때까지 상태를 조회한다. isActive가 false가 되면 null로 멈춘다.
 * 조회 통신 실패는 생성 실패와 구분하고, 작업이나 게스트 세션이 없으면 lost로 본다.
 */
const waitForGuestTrialGeneration = async (
  generationId: string,
  isActive: () => boolean,
): Promise<GuestTrialGenerationOutcome | null> => {
  while (isActive()) {
    let status: DiaryGenerationStatusResponse;
    try {
      status = await getGuestTrialGenerationStatus(generationId);
    } catch (error: unknown) {
      const isLost =
        error instanceof RequestError &&
        (error.problem.status === 404 || error.problem.status === 401);
      return isLost ? { type: 'lost' } : { type: 'checkFailed' };
    }

    if (status.status === 'SUCCEEDED') {
      return { type: 'ready', diaryId: status.diaryId };
    }
    if (status.status === 'FAILED') {
      return { type: 'failed', errorCode: status.errorCode };
    }
    await wait(POLL_INTERVAL_MS);
  }
  return null;
};

type GenerationToast = Pick<
  ToastProps,
  'type' | 'message' | 'description' | 'action'
>;

/**
 * 비로그인 체험의 생성 완료를 모든 서비스 화면에서 확인한다.
 * 회원의 DiaryGenerationNotice처럼 화면을 옮겨도 같은 토스트를 이어서 보여준다.
 */
const GuestTrialNotice = () => {
  const generationId = useGuestTrialGenerationId();
  const statusCheckRequest = useGuestTrialStatusCheckRequest();
  const navigate = useNavigate();
  const { addToast, deleteToast, updateToast } = useToastContext();

  // 같은 ID의 토스트가 있으면 제자리에서 바꾸고, 없으면 새로 띄운다.
  const showToast = (toast: GenerationToast): void => {
    const nextToast: ToastProps = {
      type: toast.type,
      message: toast.message,
      description: toast.description,
      action: toast.action,
      onClose: (): void => deleteToast(GENERATION_TOAST_ID),
    };
    updateToast(GENERATION_TOAST_ID, nextToast);
    addToast(nextToast, 'infinite', GENERATION_TOAST_ID);
  };

  const showProgress = useEffectEvent((): void => {
    showToast({
      type: 'loading',
      message: GUEST_TRIAL_COPY.generatingTitle,
      description: GUEST_TRIAL_COPY.generatingDescription,
    });
  });

  const settle = useEffectEvent(
    (outcome: GuestTrialGenerationOutcome): void => {
      switch (outcome.type) {
        case 'ready':
          saveGuestTrialDiaryId(outcome.diaryId);
          removeGuestTrialGenerationId();
          showToast({
            type: 'success',
            message: GUEST_TRIAL_COPY.readyTitle,
            description: GUEST_TRIAL_COPY.readyDescription,
            action: {
              label: GUEST_TRIAL_COPY.readyAction,
              onClick: (): void => {
                deleteToast(GENERATION_TOAST_ID);
                requestGuestTrialResultOpen();
                // 결과 시트는 피드에 있으므로 다른 화면이면 피드로 이동해 연다.
                if (window.location.pathname !== '/') void navigate('/');
              },
            },
          });
          return;
        case 'failed': {
          removeGuestTrialGenerationId();
          markGuestTrialEndedWithoutResult();
          const [message, description] =
            DIARY_GENERATION_FAILURE_MESSAGES[outcome.errorCode];
          showToast({ type: 'error', message, description });
          return;
        }
        case 'lost':
          removeGuestTrialGenerationId();
          markGuestTrialEndedWithoutResult();
          showToast({
            type: 'error',
            message: GUEST_TRIAL_COPY.generationLost,
            description: GUEST_TRIAL_COPY.generationLostDescription,
          });
          return;
        case 'checkFailed':
          // 조회 실패는 생성 실패가 아니므로 작업 ID를 남기고 다시 확인하게 한다.
          showToast({
            type: 'error',
            message: GUEST_TRIAL_COPY.statusCheckFailed,
            description: GUEST_TRIAL_COPY.statusCheckFailedDescription,
            action: {
              label: GUEST_TRIAL_COPY.statusCheckRetry,
              onClick: requestGuestTrialStatusCheck,
            },
          });
          return;
      }
    },
  );

  useEffect((): (() => void) | void => {
    // 생성 도중 로그인한 경우의 미완료 작업은 v1에서 이어가지 않는다.
    if (generationId === null || hasCompletedOAuth()) return;

    let isActive = true;
    showProgress();
    void waitForGuestTrialGeneration(
      generationId,
      (): boolean => isActive,
    ).then((outcome): void => {
      if (outcome && isActive) settle(outcome);
    });

    return (): void => {
      isActive = false;
    };
  }, [generationId, statusCheckRequest]);

  return null;
};

export default GuestTrialNotice;
