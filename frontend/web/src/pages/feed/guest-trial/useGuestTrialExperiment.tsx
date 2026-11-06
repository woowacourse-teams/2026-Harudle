import {
  useEffect,
  useEffectEvent,
  useRef,
  useState,
  type ReactNode,
  type RefObject,
} from 'react';
import { useNavigate } from 'react-router';
import type { BottomSheetHandle } from '../../../shared/BottomSheet';
import { useAnalytics } from '../../../posthog/useAnalytics';
import GuestTrialClaim from './GuestTrialClaim';
import GuestTrialGuide from './GuestTrialGuide';
import GuestTrialInputSheet from './GuestTrialInputSheet';
import { GuestTrialResultSheet } from './GuestTrialResultSheet';
import useGuestTrialSubmit, {
  type SubmitGuestTrial,
} from './useGuestTrialSubmit';
import {
  hasCompletedOAuth,
  isNewGuestTrialTarget,
  markGuestTrialGuideSeen,
  markGuestTrialResultOpenHandled,
  readGuestTrialDiaryId,
  readGuestTrialGenerationId,
  readGuestTrialResultOpenHandled,
  requestGuestTrialResultOpen,
  requestGuestTrialStatusCheck,
  useGuestTrialDiaryId,
  useGuestTrialEndedWithoutResult,
  useGuestTrialGenerationId,
  useGuestTrialResultOpenRequest,
} from './guestTrialStorage';

const GUIDE_RETRY_INTERVAL_MS = 500;

type ExperimentMode =
  | { readonly type: 'none' }
  | { readonly type: 'claim'; readonly diaryId: string }
  | { readonly type: 'trial'; readonly shouldShowGuide: boolean };

// 가이드를 본 뒤 피드를 다시 열어도 같은 방문 동안은 체험용 +를 유지한다.
let isTrialActiveThisVisit = false;

const decideMode = (): ExperimentMode => {
  const diaryId = readGuestTrialDiaryId();

  // 신규 노출 대상과 별개로, 로그인한 회원에게 남은 체험 ID는 항상 처리한다.
  // 생성 도중 로그인한 경우의 미완료 작업은 v1에서 이어가지 않는다.
  if (hasCompletedOAuth()) {
    return diaryId === null ? { type: 'none' } : { type: 'claim', diaryId };
  }

  const hasSavedTrial =
    diaryId !== null || readGuestTrialGenerationId() !== null;
  if (hasSavedTrial || isTrialActiveThisVisit) {
    return { type: 'trial', shouldShowGuide: false };
  }
  if (isNewGuestTrialTarget()) {
    return { type: 'trial', shouldShowGuide: true };
  }
  return { type: 'none' };
};

const canShowGuideNow = (): boolean => {
  return (
    document.visibilityState === 'visible' &&
    document.querySelector('dialog[open]') === null
  );
};

interface UseGuestTrialExperimentOptions {
  readonly isFeedReady: boolean;
  /** 피드의 + 버튼. 가이드가 강조할 위치를 찾는 데 쓴다. */
  readonly createButtonRef: RefObject<HTMLButtonElement | null>;
}

interface GuestTrialExperiment {
  /** true면 피드의 +가 기존 핸들러 대신 onCreate를 사용한다. */
  readonly isActive: boolean;
  readonly onCreate: () => void;
  /** 가이드·입력·결과 시트·claim 처리. 피드 어디에 두어도 화면 위에 그려진다. */
  readonly element: ReactNode;
}

/**
 * 피드 게스트 체험 실험. 피드는 isActive로 + 핸들러를 고르고 element만 렌더링한다.
 * 생성 완료 조회와 토스트는 모든 화면에 붙은 GuestTrialNotice가 맡는다.
 * 실험을 걷어낼 때는 이 폴더와 FeedPage의 연결만 지운다.
 */
const useGuestTrialExperiment = ({
  isFeedReady,
  createButtonRef,
}: UseGuestTrialExperimentOptions): GuestTrialExperiment => {
  const [mode] = useState(decideMode);
  const navigate = useNavigate();
  const { track } = useAnalytics();
  const submit = useGuestTrialSubmit();
  const diaryId = useGuestTrialDiaryId();
  const generationId = useGuestTrialGenerationId();
  const hasEndedWithoutResult = useGuestTrialEndedWithoutResult();
  const resultOpenRequest = useGuestTrialResultOpenRequest();
  // 이 피드가 열리기 전에 처리한 열기 요청으로 결과 시트를 다시 그리지 않는다.
  const [resultOpenBaseline] = useState(readGuestTrialResultOpenHandled);
  const inputSheetRef = useRef<BottomSheetHandle>(null);
  const resultSheetRef = useRef<BottomSheetHandle>(null);
  const hasShownGuideRef = useRef(false);
  const [isGuideOpen, setIsGuideOpen] = useState(false);

  const isTrial = mode.type === 'trial';
  // 최종 실패 뒤에는 환급 계약이 없으므로 기존 + 동작(로그인 안내)으로 돌아간다.
  const isActive =
    isTrial &&
    !(hasEndedWithoutResult && diaryId === null && generationId === null);
  const shouldShowGuide = isTrial && mode.shouldShowGuide;

  // 다른 화면의 완료 토스트에서 요청한 열기도 피드에 들어오면 처리한다.
  useEffect((): void => {
    if (!isTrial || diaryId === null) return;
    if (resultOpenRequest.requested <= resultOpenRequest.handled) return;

    markGuestTrialResultOpenHandled();
    resultSheetRef.current?.open();
  }, [
    isTrial,
    diaryId,
    resultOpenRequest.requested,
    resultOpenRequest.handled,
  ]);

  const openGuide = useEffectEvent((): boolean => {
    if (!canShowGuideNow()) return false;
    // 노출 기록을 남기지 못하면 다음 방문에도 반복될 수 있으므로 가이드를 보류한다.
    if (markGuestTrialGuideSeen()) {
      setIsGuideOpen(true);
      isTrialActiveThisVisit = true;
      track('guest_trial_guide_viewed');
    }
    return true;
  });

  useEffect((): (() => void) | void => {
    if (!shouldShowGuide || !isFeedReady || hasShownGuideRef.current) return;

    // 다른 dialog가 열려 있거나 탭이 가려져 있으면 조건이 맞을 때까지 기다린다.
    const intervalId = setInterval((): void => {
      if (hasShownGuideRef.current) return;
      if (openGuide()) {
        hasShownGuideRef.current = true;
        clearInterval(intervalId);
      }
    }, GUIDE_RETRY_INTERVAL_MS);

    return (): void => clearInterval(intervalId);
  }, [shouldShowGuide, isFeedReady]);

  const onCreate = (): void => {
    setIsGuideOpen(false);

    if (diaryId !== null) {
      requestGuestTrialResultOpen();
      return;
    }
    if (generationId !== null) {
      requestGuestTrialStatusCheck();
      return;
    }
    inputSheetRef.current?.open();
  };

  const handleSubmit: SubmitGuestTrial = async (sourceText) => {
    const errorMessage = await submit(sourceText);
    if (errorMessage === null) inputSheetRef.current?.close();
    return errorMessage;
  };

  const handleLogin = (): void => {
    resultSheetRef.current?.close();
    void navigate('/login');
  };

  const renderElement = (): ReactNode => {
    if (mode.type === 'claim')
      return <GuestTrialClaim diaryId={mode.diaryId} />;
    if (!isActive) return null;

    return (
      <>
        <GuestTrialGuide
          isOpen={isGuideOpen}
          anchorRef={createButtonRef}
          onStart={onCreate}
          onClose={(): void => setIsGuideOpen(false)}
        />
        <GuestTrialInputSheet ref={inputSheetRef} onSubmit={handleSubmit} />
        {diaryId !== null && (
          <GuestTrialResultSheet
            ref={resultSheetRef}
            openCount={
              resultOpenRequest.handled > resultOpenBaseline
                ? resultOpenRequest.handled
                : 0
            }
            diaryId={diaryId}
            onLogin={handleLogin}
            onKeepBrowsing={(): void => resultSheetRef.current?.close()}
          />
        )}
      </>
    );
  };

  return { isActive, onCreate, element: renderElement() };
};

export default useGuestTrialExperiment;
