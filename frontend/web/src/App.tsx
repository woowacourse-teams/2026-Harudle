import { css } from '@emotion/react';
import HomePage from './pages/home/HomePage';
import { Route, Routes } from 'react-router';
import DiaryWritePage from './pages/diary-write/DiaryWritePage';
import DiaryGeneratingPage from './pages/diary-generating/DiaryGeneratingPage';
import DiaryDetailPage from './pages/diary-detail/DiaryDetailPage';
import DiarySharePage from './pages/diary-share/DiarySharePage';

const App = () => {
  return (
    <div css={appStyle}>
      <Routes>
        <Route path="/" element={<HomePage />} />
        <Route path="/diary-write" element={<DiaryWritePage />} />
        <Route path="/diary-generating" element={<DiaryGeneratingPage />} />
        <Route path="/diaries/:diaryId" element={<DiaryDetailPage />} />
        <Route path="/shares/:shareId" element={<DiarySharePage />} />
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
