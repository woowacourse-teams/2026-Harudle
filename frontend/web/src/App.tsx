import { css } from '@emotion/react';
import { Route, Routes } from 'react-router';

import AgreementRoute from './AgreementRoute';
import PublicFeedRoute from './PublicFeedRoute';
import AdminGuard from './pages/admin/AdminGuard';
import AlbumPage from './pages/album/AlbumPage/AlbumPage';
import BlockedUsersPage from './pages/blocked-users/BlockedUsersPage';
import ConsentPage from './pages/consent/ConsentPage';
import DiaryDetailPage from './pages/diary-detail/DiaryDetailPage';
import DiaryWritePage from './pages/diary-write/DiaryWritePage';
import FeedDetailPage from './pages/feed-detail/FeedDetailPage';
import FeedPostPage from './pages/feed-post/FeedPostPage';
import FeedPage from './pages/feed/FeedPage';
import LandingRoutes from './pages/landing/LandingRoutes';
import LegalDocumentPage from './pages/legal/LegalDocumentPage';
import AuthCallbackPage from './pages/login/AuthCallbackPage';
import LoginPage from './pages/login/LoginPage';
import NotFoundPage from './pages/not-found/NotFoundPage';
import PwaAnalyticsTracker from './pages/setting/PwaAnalyticsTracker';
import SettingPage from './pages/setting/SettingPage';

const App = () => {
  return (
    <div css={appStyle}>
      <PwaAnalyticsTracker />

      <Routes>
        {/* 약관 검사 없이 접근하는 로그인·문서·동의 화면 */}
        <Route path="/landing/*" element={<LandingRoutes />} />
        <Route path="/login" element={<LoginPage />} />
        <Route path="/auth/callback" element={<AuthCallbackPage />} />
        <Route
          path="/legal/terms/:version?"
          element={<LegalDocumentPage type="TERMS" />}
        />
        <Route
          path="/legal/privacy/:version?"
          element={<LegalDocumentPage type="PRIVACY" />}
        />
        <Route path="/consent" element={<ConsentPage />} />

        {/* 모든 서비스 화면은 세션 복원 후 약관 동의를 확인한다. */}
        <Route element={<PublicFeedRoute />}>
          <Route element={<AgreementRoute />}>
            <Route path="/" element={<FeedPage />} />
            <Route path="/feeds/:feedId" element={<FeedDetailPage />} />
            <Route path="/album" element={<AlbumPage />} />
            <Route path="/diary-write" element={<DiaryWritePage />} />
            <Route path="/diary/:diaryId" element={<DiaryDetailPage />} />
            <Route
              path="/diary/:diaryId/feed-post"
              element={<FeedPostPage />}
            />
            <Route path="/setting" element={<SettingPage />} />
            <Route
              path="/setting/blocked-users"
              element={<BlockedUsersPage />}
            />
            <Route path="/admin/*" element={<AdminGuard />} />
          </Route>
        </Route>

        <Route path="*" element={<NotFoundPage />} />
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
