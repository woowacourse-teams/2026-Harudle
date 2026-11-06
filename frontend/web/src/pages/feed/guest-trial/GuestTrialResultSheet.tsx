import { css } from '@emotion/react';
import { useEffect, useState, type Ref } from 'react';
import ActionButton from '../../../shared/ActionButton';
import BottomSheet, {
  type BottomSheetHandle,
} from '../../../shared/BottomSheet';
import LoadingSpinner from '../../../shared/LoadingSpinner';
import type { ApiRequest } from '../../../shared/api';
import { useAnalytics } from '../../../posthog/useAnalytics';
import type { DiaryDetailResponse } from '../../../domain/diary/diaryDetail';
import DiaryImageDownloadButton from '../../diary-detail/DiaryImageDownloadButton';
import { theme } from '../../../styles/theme';
import { getGuestTrialDiary, type GuestTrialDiary } from './guestTrialApi';
import GuestTrialMaskedComic from './GuestTrialMaskedComic';
import { GUEST_TRIAL_COPY } from './copy';

interface FullComicProps {
  readonly imageUrl: string;
  readonly title: string;
}

/** 회원 소유가 확인된 뒤의 전체 결과. 저장 버튼과 같은 원본을 보여준다. */
const FullComic = ({ imageUrl, title }: FullComicProps) => {
  return (
    <div css={comicStyle}>
      <img src={imageUrl} alt={title} css={comicImageStyle} />
    </div>
  );
};

interface GuestTrialResultContentProps {
  readonly diaryId: string;
  readonly onLogin: () => void;
  readonly onKeepBrowsing: () => void;
}

/** 열 때마다 새로 마운트돼 최신 이미지 주소를 받고, 표시된 결과를 한 번 기록한다. */
const GuestTrialResultContent = ({
  diaryId,
  onLogin,
  onKeepBrowsing,
}: GuestTrialResultContentProps) => {
  const { track } = useAnalytics();
  const [request, setRequest] = useState<ApiRequest<GuestTrialDiary>>({
    status: 'loading',
  });
  const [attempt, setAttempt] = useState(0);
  const [hasImageError, setHasImageError] = useState(false);

  useEffect((): (() => void) => {
    let isActive = true;
    getGuestTrialDiary(diaryId)
      .then((diary): void => {
        if (isActive) setRequest({ status: 'success', data: diary });
      })
      .catch((error: unknown): void => {
        if (!isActive) return;
        setRequest({
          status: 'error',
          error:
            error instanceof Error
              ? error
              : new Error(GUEST_TRIAL_COPY.resultLoadFailed),
        });
      });

    return (): void => {
      isActive = false;
    };
  }, [diaryId, attempt]);

  const reload = (): void => {
    setHasImageError(false);
    setRequest({ status: 'loading' });
    setAttempt((current): number => current + 1);
  };

  if (request.status === 'idle' || request.status === 'loading') {
    return (
      <div css={feedbackStyle} aria-label={GUEST_TRIAL_COPY.resultLoading}>
        <LoadingSpinner />
      </div>
    );
  }

  if (request.status === 'error' || hasImageError) {
    return (
      <div css={feedbackStyle} role="alert">
        <p>{GUEST_TRIAL_COPY.resultLoadFailed}</p>
        <ActionButton
          label={GUEST_TRIAL_COPY.resultReload}
          variant="secondary"
          onClick={reload}
        />
      </div>
    );
  }

  const { generation } = request.data;
  return (
    <div css={contentStyle}>
      <h3 css={comicTitleStyle}>{generation.title}</h3>
      <GuestTrialMaskedComic
        imageUrl={generation.imageUrl}
        onLoad={(): void => {
          track('guest_trial_result_viewed', { diary_id: diaryId });
        }}
        onError={(): void => setHasImageError(true)}
      />
      <p css={unlockTitleStyle}>{GUEST_TRIAL_COPY.unlockTitle}</p>
      <p css={subtleStyle}>{GUEST_TRIAL_COPY.unlockDescription}</p>
      <div css={actionsStyle}>
        <ActionButton label={GUEST_TRIAL_COPY.loginAction} onClick={onLogin} />
        <button type="button" css={textButtonStyle} onClick={onKeepBrowsing}>
          {GUEST_TRIAL_COPY.keepBrowsingAction}
        </button>
      </div>
    </div>
  );
};

interface GuestTrialResultSheetProps extends GuestTrialResultContentProps {
  readonly ref: Ref<BottomSheetHandle>;
  /** 0이면 아직 열지 않은 상태다. 열 때마다 1씩 늘려 내용을 새로 마운트한다. */
  readonly openCount: number;
}

export const GuestTrialResultSheet = ({
  ref,
  openCount,
  ...contentProps
}: GuestTrialResultSheetProps) => {
  return (
    <BottomSheet title={GUEST_TRIAL_COPY.resultSheetTitle} ref={ref}>
      {openCount > 0 && (
        <GuestTrialResultContent key={openCount} {...contentProps} />
      )}
    </BottomSheet>
  );
};

interface GuestTrialClaimedSheetProps {
  readonly ref: Ref<BottomSheetHandle>;
  readonly diary: DiaryDetailResponse | null;
  readonly onOpenAlbum: () => void;
}

export const GuestTrialClaimedSheet = ({
  ref,
  diary,
  onOpenAlbum,
}: GuestTrialClaimedSheetProps) => {
  return (
    <BottomSheet title={GUEST_TRIAL_COPY.claimedSheetTitle} ref={ref}>
      {diary && (
        <div css={contentStyle}>
          <p css={claimedBadgeStyle}>{GUEST_TRIAL_COPY.claimedBadge}</p>
          <h3 css={comicTitleStyle}>{diary.generation.title}</h3>
          <FullComic
            imageUrl={diary.generation.imageUrl}
            title={diary.generation.title}
          />
          <p css={subtleStyle}>{GUEST_TRIAL_COPY.claimedDescription}</p>
          <div css={actionsStyle}>
            <DiaryImageDownloadButton
              diaryId={diary.id}
              imageUrl={diary.generation.imageUrl}
              diaryDate={diary.diaryDate}
              diaryTitle={diary.generation.title}
            />
            <button type="button" css={textButtonStyle} onClick={onOpenAlbum}>
              {GUEST_TRIAL_COPY.albumAction}
            </button>
          </div>
        </div>
      )}
    </BottomSheet>
  );
};

const contentStyle = css`
  display: flex;
  flex-direction: column;
  align-items: stretch;
  padding: 8px 0 calc(20px + env(safe-area-inset-bottom));
  text-align: center;
`;

const feedbackStyle = css`
  display: grid;
  gap: 16px;
  place-items: center;
  min-height: 240px;
  padding: 24px 0 calc(20px + env(safe-area-inset-bottom));
  color: ${theme.colors.foreground.neutralMuted};
  font-size: 14px;
  text-align: center;
`;

const claimedBadgeStyle = css`
  color: ${theme.colors.foreground.positive};
  font-size: 13px;
  font-weight: 600;
  line-height: 20px;
  text-align: left;
`;

const comicTitleStyle = css`
  margin: 2px 0 0;
  font-size: 20px;
  font-weight: 700;
  line-height: 30px;
  text-align: left;
  word-break: keep-all;
`;

const comicStyle = css`
  position: relative;
  width: 100%;
  max-width: 300px;
  aspect-ratio: 1;
  margin: 16px auto 0;
  overflow: hidden;
  border: 1px solid ${theme.colors.stroke.outline};
  border-radius: 12px;
  background: ${theme.colors.background.neutralWeak};
`;

const comicImageStyle = css`
  display: block;
  width: 100%;
  height: 100%;
  object-fit: cover;
`;

const unlockTitleStyle = css`
  margin-top: 20px;
  font-size: 16px;
  font-weight: 600;
  line-height: 26px;
  white-space: pre-line;
  word-break: keep-all;
`;

const subtleStyle = css`
  margin-top: 6px;
  color: ${theme.colors.foreground.neutralMuted};
  font-size: 13px;
  line-height: 20px;
`;

const actionsStyle = css`
  display: flex;
  flex-direction: column;
  gap: 4px;
  margin-top: 20px;
`;

const textButtonStyle = css`
  min-height: 44px;
  padding: 8px;
  border: 0;
  background: transparent;
  color: ${theme.colors.foreground.neutralMuted};
  font-size: 14px;
  line-height: 22px;
  cursor: pointer;
`;
