import { css } from '@emotion/react';
import requestFailImage from '../../assets/images/request-fail.png';
import { useNavigate } from 'react-router';
import backIcon from '../../assets/icons/back.svg';
import harudleLogo from '../../assets/images/harudle-logo.webp';
import { theme } from '../../styles/theme';
import { FEED_DETAIL_COPY } from './copy';

interface FeedDetailErrorProps {
  readonly errorMessage: string;
}

const FeedDetailError = ({ errorMessage }: FeedDetailErrorProps) => {
  const navigate = useNavigate();
  return (
    <div css={pageStyle}>
      <header css={pageHeaderStyle}>
        <button
          type="button"
          css={logoButtonStyle}
          onClick={() => {
            void navigate('/');
          }}
        >
          <img css={logoStyle} src={harudleLogo} alt="하루들" />
        </button>
        <button
          type="button"
          css={backButtonStyle}
          onClick={() => {
            void navigate('/');
          }}
        >
          <img src={backIcon} alt="뒤로가기 아이콘" />
        </button>
      </header>
      <div css={feedDetailErrorStyle}>
        <img
          src={requestFailImage}
          alt="네컷만화를 불러오지 못해 속상한 사람과 강아지"
          css={illustrationStyle}
        />

        <div css={messageBoxStyle}>
          <h2 css={titleStyle}>{FEED_DETAIL_COPY.loadErrorTitle}</h2>
          <p css={descriptionStyle}>{errorMessage}</p>
        </div>

        <button
          type="button"
          css={retryButtonStyle}
          onClick={() => {
            window.location.reload();
          }}
        >
          {FEED_DETAIL_COPY.reloadAction}
        </button>
      </div>
    </div>
  );
};

export default FeedDetailError;

const feedDetailErrorStyle = css`
  display: flex;
  flex-direction: column;
  justify-content: start;
  align-items: center;
  gap: 16px;
  width: 100%;
  height: 100%;
  padding: 24px 20px;
  overflow-y: auto;
`;

const illustrationStyle = css`
  width: 240px;
  height: 300px;
  object-fit: contain;
`;

const messageBoxStyle = css`
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 8px;
  text-align: center;
`;

const titleStyle = css`
  word-break: keep-all;
  color: ${theme.colors.foreground.neutral};
  font-size: 20px;
  font-weight: 700;
  line-height: 30px;
`;

const descriptionStyle = css`
  max-width: 280px;
  color: ${theme.colors.foreground.neutralMuted};
  font-size: 14px;
  font-weight: 400;
  line-height: 22px;
  word-break: keep-all;
`;

const retryButtonStyle = css`
  word-break: keep-all;
  width: 144px;
  height: 48px;
  border: none;
  border-radius: 18px;
  background-color: ${theme.colors.background.brandSolid};
  color: ${theme.colors.foreground.onBrand};
  font-size: 15px;
  font-weight: 600;
  cursor: pointer;

  &:active {
    transform: scale(0.98);
  }
`;

const pageStyle = css`
  display: flex;
  flex-direction: column;
  height: 100%;
  background-color: ${theme.colors.background.surface};
  color: ${theme.colors.foreground.neutral};
`;

const pageHeaderStyle = css`
  position: relative;
  flex-shrink: 0;
  width: 100%;
  height: 56px;
`;

const logoButtonStyle = css`
  display: flex;
  justify-content: center;
  align-items: center;
  width: 100%;
  height: 100%;
  border: none;
  background: none;
  cursor: pointer;
`;

const logoStyle = css`
  width: 106px;
  height: 71px;
`;

const backButtonStyle = css`
  position: absolute;
  top: 8px;
  left: 8px;
  display: flex;
  align-items: center;
  justify-content: center;
  width: 40px;
  height: 40px;
  padding: 0;
  border: none;
  background: none;
  cursor: pointer;
`;
