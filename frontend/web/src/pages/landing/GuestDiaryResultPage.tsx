import { css } from '@emotion/react';
import { useState } from 'react';
import { useParams } from 'react-router';
import loadingAnimation from '../../assets/images/loading-animation.webp';
import { theme } from '../../styles/theme';
import LandingLoginCta from './LandingLoginCta';
import { isGuestTrialAlreadyUsedError } from './guestTrialErrors';
import type { GuestDiaryResponse } from './guestTrialApi';
import useGuestDiaryResult from './useGuestDiaryResult';
import { GUEST_TRIAL_COPY } from './trialCopy';

const GuestDiaryResultPage = () => {
  const { diaryId } = useParams();
  const { resultRequest, retryResult } = useGuestDiaryResult({ diaryId });

  if (resultRequest.status === 'idle' || resultRequest.status === 'loading') {
    return (
      <div css={feedbackPageStyle}>
        <img src={loadingAnimation} alt="로딩 중" css={loadingImageStyle} />
        <p css={feedbackTitleStyle}>{GUEST_TRIAL_COPY.resultLoading}</p>
      </div>
    );
  }

  if (resultRequest.status === 'error') {
    return (
      <div css={feedbackPageStyle}>
        <h1 css={feedbackTitleStyle}>
          {isGuestTrialAlreadyUsedError(resultRequest.error)
            ? '게스트 체험을 이미 사용했어요'
            : GUEST_TRIAL_COPY.resultFailure}
        </h1>
        <p css={feedbackMessageStyle}>{resultRequest.error.message}</p>
        {isGuestTrialAlreadyUsedError(resultRequest.error) ? (
          <LandingLoginCta
            label="카카오로 로그인하기"
            analyticsEvent="landing_trial_login_clicked"
            location="already_used"
          />
        ) : (
          <button
            type="button"
            css={feedbackRetryButtonStyle}
            onClick={retryResult}
          >
            다시 시도
          </button>
        )}
      </div>
    );
  }

  return <GuestDiaryResult diary={resultRequest.data} onRetry={retryResult} />;
};

const GuestDiaryResult = ({
  diary,
  onRetry,
}: {
  diary: GuestDiaryResponse;
  onRetry: () => void;
}) => {
  const [imageStatus, setImageStatus] = useState<
    'loading' | 'loaded' | 'error'
  >('loading');

  if (imageStatus === 'loading') {
    return (
      <div css={feedbackPageStyle}>
        <img
          src={diary.generation.imageUrl}
          alt=""
          aria-hidden="true"
          data-testid="guest-diary-result-preload"
          css={preloadImageStyle}
          onLoad={() => setImageStatus('loaded')}
          onError={() => setImageStatus('error')}
        />
        <img src={loadingAnimation} alt="로딩 중" css={loadingImageStyle} />
        <p css={feedbackTitleStyle}>{GUEST_TRIAL_COPY.resultLoading}</p>
      </div>
    );
  }

  if (imageStatus === 'error') {
    return (
      <div css={feedbackPageStyle} role="alert">
        <h1 css={feedbackTitleStyle}>결과 이미지를 불러오지 못했어요</h1>
        <p css={feedbackMessageStyle}>잠시 후 다시 시도해주세요</p>
        <button type="button" css={feedbackRetryButtonStyle} onClick={onRetry}>
          다시 시도
        </button>
      </div>
    );
  }

  return (
    <main css={pageStyle}>
      <header css={headerStyle}>
        <p css={dateStyle}>{diary.diaryDate}</p>
        <h1 css={titleStyle}>{diary.generation.title}</h1>
      </header>

      <img
        src={diary.generation.imageUrl}
        alt={`${diary.generation.title} 네컷만화`}
        css={diaryImageStyle}
        onError={() => setImageStatus('error')}
      />

      <section css={storyStyle}>
        <h2 css={storyTitleStyle}>{GUEST_TRIAL_COPY.resultContentTitle}</h2>
        <p css={storyTextStyle}>{diary.sourceText}</p>
      </section>

      <section css={ctaSectionStyle}>
        <p css={ctaDescriptionStyle}>{GUEST_TRIAL_COPY.loginNotice}</p>
        <LandingLoginCta
          label="카카오로 로그인하기"
          analyticsEvent="landing_trial_login_clicked"
          location="result"
        />
      </section>
    </main>
  );
};

export default GuestDiaryResultPage;

const pageStyle = css`
  display: flex;
  flex-direction: column;
  gap: 20px;
  width: 100%;
  min-height: 100%;
  padding: 28px 20px 36px;
  overflow-y: auto;
  background-color: ${theme.colors.background.surface};
`;

const headerStyle = css`
  display: flex;
  flex-direction: column;
  gap: 4px;
  text-align: center;
`;

const dateStyle = css`
  color: ${theme.colors.foreground.neutralMuted};
  font-size: 14px;
  line-height: 22px;
`;

const titleStyle = css`
  color: ${theme.colors.foreground.neutral};
  font-size: 26px;
  font-weight: 700;
  line-height: 38px;
  overflow-wrap: anywhere;
`;

const diaryImageStyle = css`
  width: 100%;
  aspect-ratio: 1;
  border: 1px solid ${theme.colors.stroke.outline};
  border-radius: 20px;
  object-fit: cover;
`;

const storyStyle = css`
  display: flex;
  flex-direction: column;
  gap: 8px;
`;

const storyTitleStyle = css`
  color: ${theme.colors.foreground.neutral};
  font-size: 18px;
  font-weight: 700;
  line-height: 28px;
`;

const storyTextStyle = css`
  color: ${theme.colors.foreground.neutral};
  font-size: 16px;
  line-height: 26px;
  white-space: pre-wrap;
  overflow-wrap: anywhere;
`;

const ctaSectionStyle = css`
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 12px;
  margin-top: 8px;
  padding-top: 20px;
  border-top: 1px solid ${theme.colors.stroke.divider};
`;

const ctaDescriptionStyle = css`
  color: ${theme.colors.foreground.neutralMuted};
  font-size: 14px;
  line-height: 22px;
  text-align: center;
  word-break: keep-all;
`;

const feedbackPageStyle = css`
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 16px;
  width: 100%;
  min-height: 100%;
  padding: 32px 24px;
  background-color: ${theme.colors.background.surface};
  text-align: center;
`;

const loadingImageStyle = css`
  width: 140px;
  height: 140px;
`;

const preloadImageStyle = css`
  position: absolute;
  width: 1px;
  height: 1px;
  opacity: 0;
  pointer-events: none;
`;

const feedbackTitleStyle = css`
  color: ${theme.colors.foreground.neutral};
  font-size: 22px;
  font-weight: 700;
  line-height: 34px;
  word-break: keep-all;
`;

const feedbackMessageStyle = css`
  color: ${theme.colors.foreground.neutralMuted};
  font-size: 15px;
  line-height: 24px;
  word-break: keep-all;
`;

const feedbackRetryButtonStyle = css`
  min-width: 160px;
  min-height: 48px;
  padding: 12px 20px;
  border: none;
  border-radius: 14px;
  background-color: ${theme.colors.background.brandSolid};
  color: ${theme.colors.foreground.onBrand};
  font-size: 15px;
  font-weight: 700;
  line-height: 24px;
  cursor: pointer;
`;
