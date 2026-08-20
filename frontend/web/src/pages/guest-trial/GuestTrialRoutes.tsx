import { css } from '@emotion/react';
import { Route, Routes } from 'react-router';
import loadingAnimation from '../../assets/images/loading-animation.webp';
import { theme } from '../../styles/theme';
import GuestDiaryResultPage from './GuestDiaryResultPage';
import GuestDiaryWritePage from './GuestDiaryWritePage';
import useGuestEntry from './useGuestEntry';

const GuestTrialRoutes = () => {
  const { guestEntryRequest } = useGuestEntry();

  if (
    guestEntryRequest.status === 'idle' ||
    guestEntryRequest.status === 'loading'
  ) {
    return (
      <div css={feedbackPageStyle}>
        <img src={loadingAnimation} alt="로딩 중" css={loadingImageStyle} />
        <p css={feedbackTitleStyle}>게스트 체험을 준비하고 있어요</p>
      </div>
    );
  }

  if (guestEntryRequest.status === 'error') {
    return (
      <div css={feedbackPageStyle}>
        <h1 css={feedbackTitleStyle}>게스트 체험을 시작하지 못했어요</h1>
        <p css={feedbackMessageStyle}>{guestEntryRequest.error.message}</p>
      </div>
    );
  }

  return (
    <Routes>
      <Route index element={<GuestDiaryWritePage />} />
      <Route path="result/:diaryId" element={<GuestDiaryResultPage />} />
    </Routes>
  );
};

export default GuestTrialRoutes;

const feedbackPageStyle = css`
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 16px;
  width: 100%;
  min-height: 100%;
  padding: 32px 24px;
  background-color: #ffffff;
  text-align: center;
`;

const loadingImageStyle = css`
  width: 140px;
  height: 140px;
`;

const feedbackTitleStyle = css`
  color: ${theme.colors.text.primary};
  font-size: 22px;
  font-weight: 700;
  line-height: 34px;
  word-break: keep-all;
`;

const feedbackMessageStyle = css`
  color: ${theme.colors.text.secondary};
  font-size: 15px;
  line-height: 24px;
  word-break: keep-all;
`;
