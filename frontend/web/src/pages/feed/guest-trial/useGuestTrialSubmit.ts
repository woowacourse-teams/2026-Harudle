import { useCallback, useRef } from 'react';
import { RequestError } from '../../../shared/api';
import { useAnalytics } from '../../../posthog/useAnalytics';
import { getKoreanToday } from '../../landing/guestDiaryValidation';
import { issueGuestSession, submitGuestTrialDiary } from './guestTrialApi';
import { saveGuestTrialGenerationId } from './guestTrialStorage';
import { GUEST_TRIAL_COPY } from './copy';

/** 접수 실패 시 입력란 아래에 보여줄 문구, 접수 성공 시 null을 돌려준다. */
export type SubmitGuestTrial = (sourceText: string) => Promise<string | null>;

interface SubmissionAttempt {
  readonly sourceText: string;
  readonly idempotencyKey: string;
  readonly diaryDate: string;
}

// 접수 응답을 확인하기 전에는 피드를 다시 열어도 같은 요청을 재사용한다.
let pendingAttempt: SubmissionAttempt | null = null;

const getSubmitErrorMessage = (error: unknown): string => {
  if (!(error instanceof RequestError)) return GUEST_TRIAL_COPY.submitFailed;

  switch (error.problem.code) {
    case 'GENERATION_CAPACITY_EXCEEDED':
      return GUEST_TRIAL_COPY.capacityExceeded;
    case 'GUEST_TRIAL_ALREADY_USED':
      return GUEST_TRIAL_COPY.trialAlreadyUsed;
    case 'VALIDATION_ERROR':
      return error.message;
    default:
      return GUEST_TRIAL_COPY.submitFailed;
  }
};

/**
 * 생성 요청을 접수하고 작업 ID를 sessionStorage에 남긴다.
 * 완료 조회와 토스트는 모든 화면에 붙은 GuestTrialNotice가 이어서 맡는다.
 */
const useGuestTrialSubmit = (): SubmitGuestTrial => {
  const { track } = useAnalytics();
  const isSubmittingRef = useRef(false);
  const hasGuestSessionRef = useRef(false);

  return useCallback<SubmitGuestTrial>(
    async (sourceText) => {
      if (isSubmittingRef.current) return null;
      isSubmittingRef.current = true;

      // 응답을 잃은 같은 제출을 다시 보낼 때는 보관한 키로 중복 생성을 막는다.
      const previousAttempt = pendingAttempt;
      const attempt =
        previousAttempt?.sourceText === sourceText
          ? previousAttempt
          : {
              sourceText,
              idempotencyKey: crypto.randomUUID(),
              diaryDate: getKoreanToday(),
            };
      pendingAttempt = attempt;
      if (attempt !== previousAttempt) track('guest_trial_submitted');

      try {
        if (!hasGuestSessionRef.current) {
          await issueGuestSession();
          hasGuestSessionRef.current = true;
        }
        const { generationId } = await submitGuestTrialDiary({
          request: {
            diaryDate: attempt.diaryDate,
            sourceText: attempt.sourceText,
          },
          idempotencyKey: attempt.idempotencyKey,
        });
        if (pendingAttempt === attempt) pendingAttempt = null;
        saveGuestTrialGenerationId(generationId);
        return null;
      } catch (error: unknown) {
        return getSubmitErrorMessage(error);
      } finally {
        isSubmittingRef.current = false;
      }
    },
    [track],
  );
};

export default useGuestTrialSubmit;
