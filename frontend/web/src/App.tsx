import { css } from '@emotion/react';
import HomePage from './pages/home/HomePage';
import { Route, Routes } from 'react-router';
import DiaryWritePage from './pages/diary-write/DiaryWritePage';

const App = () => {
  return (
    <div css={appStyle}>
      <Routes>
        <Route path="/" element={<HomePage />} />
        <Route path="/diary-write" element={<DiaryWritePage />} />
      </Routes>
    </div>
  );
};

export default App;

const appStyle = css`
  width: 100%;
  max-width: 430px;
  height: 100%
  margin: 0 auto;
`;
