import { css } from '@emotion/react';
import HomePage from './pages/home/HomePage/HomePage';
import { Route, Routes } from 'react-router';
import DiaryWritePage from './pages/diary-write/DiaryWritePage';
import DiaryGeneratingPage from './pages/diary-generating/DiaryGeneratingPage';

import SettingPage from './pages/setting/SettingPage';
import LoginPage from './pages/login/LoginPage';
import DiaryDetailPage from './pages/diary-detail/DiaryDetailPage';
import DiarySharePage from './pages/diary-share/DiarySharePage';
import MockKakaoLoginPage from './pages/login/MockKakaoLoginPage';
import AuthCallbackPage from './pages/login/AuthCallbackPage';

const App = () => {
  return (
    <div css={appStyle}>
      <Routes>
        <Route path="/login" element={<LoginPage />} />
        {/* 이거 나중에 지워야됨 임시임 */}
        <Route
          path="/oauth2/authorization/kakao"
          element={<MockKakaoLoginPage />}
        />
        <Route path="/auth/callback" element={<AuthCallbackPage />} />
        <Route path="/" element={<HomePage />} />
        <Route path="/diary-write" element={<DiaryWritePage />} />
        <Route path="/diary-generating" element={<DiaryGeneratingPage />} />
        <Route path="/diary/:diaryId" element={<DiaryDetailPage />} />
        <Route path="/shares/:shareId" element={<DiarySharePage />} />
        <Route path="/setting" element={<SettingPage />} />
      </Routes>
    </div>
  );
};

export default App;

const appStyle = css`
  width: 100%;
  max-width: 430px;
  height: 100%;
  margin: 0 auto;
`;
