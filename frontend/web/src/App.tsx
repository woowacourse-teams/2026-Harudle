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
import LegalDocumentPage from './pages/legal/LegalDocumentPage';
import AgreementRoute from './AgreementRoute';
import ConsentPage from './pages/consent/ConsentPage';

const App = () => {
  return (
    <div css={appStyle}>
      <PwaAnalyticsTracker />

      <Routes>
        <Route element={<PublicFeedRoute />}>
          <Route element={<AgreementRoute />}>
            <Route element={<EntryRoute />}>
              <Route path="/" element={<FeedPage />} />
            </Route>
          </Route>
        </Route>
        <Route element={<PublicFeedRoute />}>
          <Route element={<AgreementRoute />}>
            <Route path="/feeds/:feedId" element={<FeedDetailPage />} />
          </Route>
        </Route>
        <Route element={<AgreementRoute />}>
          <Route path="/album" element={<AlbumPage />} />
          <Route path="/diary-write" element={<DiaryWritePage />} />
          <Route path="/diary/:diaryId" element={<DiaryDetailPage />} />
          <Route path="/diary/:diaryId/feed-post" element={<FeedPostPage />} />
          <Route path="/setting" element={<SettingPage />} />
          <Route path="/setting/blocked-users" element={<BlockedUsersPage />} />
          <Route path="/admin/*" element={<AdminGuard />} />
        </Route>
        <Route
          path="/legal/terms"
          element={<LegalDocumentPage type="TERMS" />}
        />
        <Route
          path="/legal/terms/:version"
          element={<LegalDocumentPage type="TERMS" />}
        />
        <Route
          path="/legal/privacy"
          element={<LegalDocumentPage type="PRIVACY" />}
        />
        <Route
          path="/legal/privacy/:version"
          element={<LegalDocumentPage type="PRIVACY" />}
        />
        <Route path="/consent" element={<ConsentPage />} />
        <Route path="/login" element={<LoginPage />} />
        <Route path="/auth/callback" element={<AuthCallbackPage />} />
        <Route path="/landing/*" element={<LandingRoutes />} />
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
