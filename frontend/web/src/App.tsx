import { css } from '@emotion/react';
import HomePage from './pages/home/HomePage/HomePage';
import { Route, Routes } from 'react-router';
import DiaryWritePage from './pages/diary-write/DiaryWritePage';
import DiaryGeneratingPage from './pages/diary-generating/DiaryGeneratingPage';

import SettingPage from './pages/setting/SettingPage';
import LoginPage from './pages/login/LoginPage';
import DiaryDetailPage from './pages/diary-detail/DiaryDetailPage';
import AuthCallbackPage from './pages/login/AuthCallbackPage';
import GuestTrialRoutes from './pages/guest-trial/GuestTrialRoutes';
import LandingPage from './pages/landing/LandingPage';
import AdminGuard from './pages/admin/AdminGuard';
import NotFoundPage from './pages/not-found/NotFoundPage';
import PwaAnalyticsTracker from './pages/setting/PwaAnalyticsTracker';
import SharedDiaryPage from './pages/shared-diary/SharedDiaryPage';
import ImageOutageNotice from './ImageOutageNotice';

const App = () => {
  return (
    <div css={appStyle}>
      <PwaAnalyticsTracker />
      <ImageOutageNotice />
      <div css={routeContentStyle}>
        <Routes>
          <Route path="/login" element={<LoginPage />} />
          <Route path="/auth/callback" element={<AuthCallbackPage />} />
          <Route path="/" element={<HomePage />} />
          <Route path="/diary-write" element={<DiaryWritePage />} />
          <Route path="/diary-generating" element={<DiaryGeneratingPage />} />
          <Route path="/diary/:diaryId" element={<DiaryDetailPage />} />
          <Route path="/shares/:shareId" element={<SharedDiaryPage />} />
          <Route path="/setting" element={<SettingPage />} />
          <Route path="/landing" element={<LandingPage />} />
          <Route path="/landing-try/*" element={<GuestTrialRoutes />} />
          <Route path="/admin/*" element={<AdminGuard />} />
          <Route path="*" element={<NotFoundPage />} />
        </Routes>
      </div>
    </div>
  );
};

export default App;

const appStyle = css`
  display: flex;
  flex-direction: column;
  width: 100%;
  max-width: 430px;
  height: 100%;
  margin: 0 auto;
`;

const routeContentStyle = css`
  flex: 1;
  min-height: 0;
`;
