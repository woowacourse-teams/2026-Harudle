import { css } from '@emotion/react';
import EntryRoute from './EntryRoute';
import PublicFeedRoute from './PublicFeedRoute';
import AlbumPage from './pages/album/AlbumPage/AlbumPage';
import FeedPage from './pages/feed/FeedPage';
import FeedDetailPage from './pages/feed-detail/FeedDetailPage';
import { Route, Routes } from 'react-router';
import DiaryWritePage from './pages/diary-write/DiaryWritePage';

import SettingPage from './pages/setting/SettingPage';
import BlockedUsersPage from './pages/blocked-users/BlockedUsersPage';
import LoginPage from './pages/login/LoginPage';
import DiaryDetailPage from './pages/diary-detail/DiaryDetailPage';
import AuthCallbackPage from './pages/login/AuthCallbackPage';
import LandingRoutes from './pages/landing/LandingRoutes';
import AdminGuard from './pages/admin/AdminGuard';
import NotFoundPage from './pages/not-found/NotFoundPage';
import PwaAnalyticsTracker from './pages/setting/PwaAnalyticsTracker';
import FeedPostPage from './pages/feed-post/FeedPostPage';
import DiaryGenerationNotice from './shared/DiaryGenerationNotice';

const App = () => {
  return (
    <div css={appStyle}>
      <PwaAnalyticsTracker />
      <DiaryGenerationNotice />

      <Routes>
        <Route element={<EntryRoute />}>
          <Route element={<PublicFeedRoute />}>
            <Route path="/" element={<FeedPage />} />
          </Route>
        </Route>
        <Route path="/album" element={<AlbumPage />} />
        <Route element={<PublicFeedRoute />}>
          <Route path="/feeds/:feedId" element={<FeedDetailPage />} />
        </Route>
        <Route path="/login" element={<LoginPage />} />
        <Route path="/auth/callback" element={<AuthCallbackPage />} />
        <Route path="/diary-write" element={<DiaryWritePage />} />
        <Route path="/diary/:diaryId" element={<DiaryDetailPage />} />
        <Route path="/diary/:diaryId/feed-post" element={<FeedPostPage />} />
        <Route path="/setting" element={<SettingPage />} />
        <Route path="/setting/blocked-users" element={<BlockedUsersPage />} />
        <Route path="/landing/*" element={<LandingRoutes />} />
        <Route path="/admin/*" element={<AdminGuard />} />
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
