import { useEffect, type JSX } from 'react';
import { css } from '@emotion/react';
import { Route, Routes } from 'react-router';
import loadingAnimation from '../../assets/images/loading-animation.webp';
import { theme } from '../../styles/theme';
import GuestDiaryResultPage from './GuestDiaryResultPage';
import LandingPage from './LandingPage';
import useGuestEntry from './useGuestEntry';
import NotFoundPage from '../not-found/NotFoundPage';

const LandingRoutes = (): JSX.Element => {
  const { guestEntryRequest, retryGuestEntry } = useGuestEntry();

  useEffect(() => {
    if (guestEntryRequest.status === 'error') {
      console.error('게스트 체험 진입에 실패했습니다', guestEntryRequest.error);
    }
  }, [guestEntryRequest]);

  const entryFeedback =
    guestEntryRequest.status === 'idle' ||
    guestEntryRequest.status === 'loading' ? (
      <div css={feedbackPageStyle}>
        <img src={loadingAnimation} alt="로딩 중" css={loadingImageStyle} />
        <p css={feedbackTitleStyle}>게스트 체험을 준비하고 있어요</p>
      </div>
    ) : guestEntryRequest.status === 'error' ? (
      <div css={feedbackPageStyle}>
        <h2 css={feedbackTitleStyle}>게스트 체험을 시작하지 못했어요</h2>
        <p css={feedbackMessageStyle}>잠시 후 다시 시도해주세요</p>
        <button
          type="button"
          css={feedbackRetryButtonStyle}
          onClick={retryGuestEntry}
        >
          다시 시도
        </button>
      </div>
    ) : null;

  return (
    <Routes>
      <Route index element={<LandingPage entryFeedback={entryFeedback} />} />
      <Route
        path="result/:diaryId"
        element={entryFeedback ?? <GuestDiaryResultPage />}
      />
      <Route path="*" element={<NotFoundPage />} />
    </Routes>
  );
};

export default LandingRoutes;

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
