import { css } from '@emotion/react';
import requestFailImage from '../../assets/images/request-fail.png';
import { theme } from '../../styles/theme';
import PageHeader from '../../shared/PageHeader';
import backIcon from '../../assets/icons/back.svg';
import { useNavigate } from 'react-router';

const DiaryDetailError = ({ errorMessage }: { errorMessage: string }) => {
  const navigate = useNavigate();
  return (
    <div css={diaryDetailErrorStyle} role="alert">
      <PageHeader
        left={
          <button
            type="button"
            aria-label="뒤로 가기"
            css={headerButtonStyle}
            onClick={() => navigate('/')}
          >
            <img
              src={backIcon}
              alt="뒤로가기 아이콘"
              css={headerButtonIconStyle}
            />
          </button>
        }
        title={null}
        right={null}
      />
      <img
        src={requestFailImage}
        alt="일기를 불러오지 못해 속상한 사람과 강아지"
        css={illustrationStyle}
      />

      <div css={messageBoxStyle}>
        <h2 css={titleStyle}>일기를 불러오지 못했어요</h2>
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

export default DiaryDetailError;

const diaryDetailErrorStyle = css`
  display: flex;
  flex-direction: column;
  justify-content: start;
  align-items: center;
  gap: 16px;
  width: 100%;
  height: 100%;
  padding: 24px 20px;
`;

const headerButtonStyle = css`
  display: flex;
  align-items: center;
  justify-content: center;
  width: 32px;
  height: 32px;
  padding: 0;
  border: none;
  background-color: transparent;
  cursor: pointer;
`;

const headerButtonIconStyle = css`
  width: 24px;
  height: 24px;
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
