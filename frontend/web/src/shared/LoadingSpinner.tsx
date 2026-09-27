import { css } from '@emotion/react';
import loadingSpinner from '../assets/images/loading-animation.webp';

const LoadingSpinner = () => {
  return (
    <div css={loadingSpinnerBoxStyle}>
      <img src={loadingSpinner} alt="로딩 중" css={loadingImageStyle} />
    </div>
  );
};

export default LoadingSpinner;

const loadingSpinnerBoxStyle = css`
  display: flex;
  justify-content: center;
  align-items: center;
  width: 100%;
  height: 100%;
`;

const loadingImageStyle = css`
  width: 140px;
  height: 140px;
`;
