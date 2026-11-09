import { useEffect, useEffectEvent, useRef, useState } from 'react';
import { useNavigate } from 'react-router';
import type { BottomSheetHandle } from '../../../shared/BottomSheet';
import { useToastContext } from '../../../shared/ToastContext';
import type { ToastProps } from '../../../shared/Toast';
import { useAnalytics } from '../../../posthog/useAnalytics';
import type { DiaryDetailResponse } from '../../../domain/diary/diaryDetail';
import { GuestTrialClaimedSheet } from './GuestTrialResultSheet';
import {
  resolveGuestTrialClaim,
  type GuestTrialClaimOutcome,
} from './guestTrialApi';
import { removeGuestTrialDiaryId } from './guestTrialStorage';
import { GUEST_TRIAL_COPY } from './copy';

const CLAIM_TOAST_ID = 'guest-trial-claim';

// 다시 들어온 피드도 진행 중인 요청의 결과를 함께 받는다.
let pendingClaim: {
  readonly diaryId: string;
  readonly promise: Promise<GuestTrialClaimOutcome>;
} | null = null;

const getClaimPromise = (
  diaryId: string,
  onResult: (outcome: GuestTrialClaimOutcome) => void,
): Promise<GuestTrialClaimOutcome> => {
  if (pendingClaim?.diaryId === diaryId) return pendingClaim.promise;

  const promise = resolveGuestTrialClaim(diaryId)
    .then((outcome): GuestTrialClaimOutcome => {
      onResult(outcome);
      return outcome;
    })
    .finally((): void => {
      if (pendingClaim?.promise === promise) pendingClaim = null;
    });
  pendingClaim = { diaryId, promise };
  return promise;
};

interface GuestTrialClaimProps {
  readonly diaryId: string;
}

/** 로그인·약관 확인을 마친 홈에서 완료된 체험 일기를 진행 표시 없이 가져온다. */
const GuestTrialClaim = ({ diaryId }: GuestTrialClaimProps) => {
  const navigate = useNavigate();
  const { track } = useAnalytics();
  const { addToast, deleteToast } = useToastContext();
  const sheetRef = useRef<BottomSheetHandle>(null);
  const [claimedDiary, setClaimedDiary] = useState<DiaryDetailResponse | null>(
    null,
  );

  const closeClaimToast = (): void => deleteToast(CLAIM_TOAST_ID);

  const showClaimToast = (toast: Omit<ToastProps, 'onClose'>): void => {
    closeClaimToast();
    addToast(
      { ...toast, onClose: closeClaimToast },
      'infinite',
      CLAIM_TOAST_ID,
    );
  };

  const handleOutcome = (
    outcome: GuestTrialClaimOutcome,
    retry: () => void,
  ): void => {
    switch (outcome.type) {
      case 'claimed':
        // 회원 소유 결과를 확보한 뒤에만 ID를 지운다.
        removeGuestTrialDiaryId();
        setClaimedDiary(outcome.diary);
        sheetRef.current?.open();
        return;
      case 'retryable':
        showClaimToast({
          type: 'error',
          message: GUEST_TRIAL_COPY.claimFailed,
          description: GUEST_TRIAL_COPY.claimFailedDescription,
          action: {
            label: GUEST_TRIAL_COPY.claimRetry,
            onClick: (): void => {
              deleteToast(CLAIM_TOAST_ID);
              retry();
            },
          },
        });
        return;
      case 'reauth':
        showClaimToast({
          type: 'error',
          message: GUEST_TRIAL_COPY.claimReauth,
          description: GUEST_TRIAL_COPY.claimReauthDescription,
          action: {
            label: GUEST_TRIAL_COPY.claimReauthAction,
            onClick: (): void => {
              deleteToast(CLAIM_TOAST_ID);
              void navigate('/login');
            },
          },
        });
        return;
      case 'terms':
        void navigate('/consent');
        return;
      case 'ignored':
        // 가져올 일기가 없으면 안내 없이 ID만 정리해 다음 진입에서 반복하지 않는다.
        removeGuestTrialDiaryId();
        return;
      case 'terminal':
        // 종료 오류는 다음 홈 진입에서 반복하지 않도록 ID를 정리한다.
        removeGuestTrialDiaryId();
        showClaimToast({
          type: 'error',
          message: outcome.message,
          description:
            outcome.reason === 'alreadyClaimed'
              ? GUEST_TRIAL_COPY.claimAlreadyClaimedDescription
              : outcome.reason === 'limitExceeded'
                ? GUEST_TRIAL_COPY.claimLimitExceededDescription
                : undefined,
          action:
            outcome.reason === 'limitExceeded'
              ? {
                  label: GUEST_TRIAL_COPY.claimLimitAction,
                  onClick: (): void => {
                    deleteToast(CLAIM_TOAST_ID);
                    void navigate('/album');
                  },
                }
              : undefined,
        });
        return;
    }
  };

  // 토스트의 재시도 액션에서도 호출하므로 렌더 상태 대신 고정된 값만 사용한다.
  const attemptClaim = async (isActive: () => boolean): Promise<void> => {
    const outcome = await getClaimPromise(diaryId, (result): void => {
      track('guest_trial_claim_result', {
        diary_id: diaryId,
        success: result.type === 'claimed',
        error_code: result.type === 'claimed' ? null : result.errorCode,
      });
    });
    if (!isActive()) return;
    handleOutcome(outcome, (): void => void attemptClaim(isActive));
  };

  const claimOnMount = useEffectEvent((isActive: () => boolean): void => {
    void attemptClaim(isActive);
  });
  const removeClaimToast = useEffectEvent((): void => closeClaimToast());

  // 재렌더마다 다시 요청하지 않도록 마운트 때 한 번만 시도한다.
  useEffect((): (() => void) => {
    let isActive = true;
    claimOnMount((): boolean => isActive);

    return (): void => {
      isActive = false;
      removeClaimToast();
    };
  }, []);

  return (
    <GuestTrialClaimedSheet
      ref={sheetRef}
      diary={claimedDiary}
      onOpenAlbum={(): void => {
        sheetRef.current?.close();
        void navigate('/album');
      }}
    />
  );
};

export default GuestTrialClaim;
