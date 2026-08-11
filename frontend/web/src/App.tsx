import { css } from '@emotion/react';
import HomePage from './pages/HomePage';

const App = () => {
  return (
    <div css={appStyle}>
      <HomePage />
    </div>
  );
};

export default App;

const appStyle = css`
  width: 100%;
  max-width: 430px;
  height:100%
  margin: 0 auto;
`;
