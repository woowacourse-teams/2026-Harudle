import { css } from '@emotion/react';
import requestFailImage from '../../assets/images/request-fail.png';
import { theme } from '../../styles/theme';

const SettingError = ({ errorMessage }: { errorMessage: string }) => {
  return (
    <div css={settingErrorStyle} role="alert">
      <img
        src={requestFailImage}
        alt="설정을 불러오지 못해 속상한 사람과 강아지"
        css={illustrationStyle}
      />

      <div css={messageBoxStyle}>
        <h2 css={titleStyle}>설정을 불러오지 못했어요</h2>
        <p css={descriptionStyle}>{errorMessage}</p>
      </div>

      <button
        type="button"
        css={retryButtonStyle}
        onClick={() => {
          window.location.reload();
        }}
      >
        다시 불러오기
      </button>
    </div>
  );
};

export default SettingError;

const settingErrorStyle = css`
  display: flex;
  flex-direction: column;
  justify-content: center;
  align-items: center;
  gap: 16px;
  width: 100%;
  height: 100%;
  padding: 24px 20px;
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
  color: ${theme.colors.text.primary};
  font-size: 20px;
  font-weight: 700;
  line-height: 30px;
`;

const descriptionStyle = css`
  max-width: 280px;
  color: ${theme.colors.text.secondary};
  font-size: 14px;
  font-weight: 400;
  line-height: 22px;
  word-break: keep-all;
`;

const retryButtonStyle = css`
  width: 144px;
  height: 48px;
  border: none;
  border-radius: 18px;
  background-color: ${theme.colors.bg.brand};
  color: #ffffff;
  font-size: 15px;
  font-weight: 600;
  cursor: pointer;

  &:active {
    transform: scale(0.98);
  }
`;
