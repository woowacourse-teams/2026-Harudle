import { css } from '@emotion/react';
import { useNavigate } from 'react-router';
import requestFailImage from '../../assets/images/request-fail.png';
import { theme } from '../../styles/theme';

const NotFoundPage = () => {
  const navigate = useNavigate();

  return (
    <main css={notFoundPageStyle}>
      <img
        src={requestFailImage}
        alt="페이지를 찾지 못해 속상한 사람과 강아지"
        css={illustrationStyle}
      />

      <div css={messageBoxStyle}>
        <h1 css={titleStyle}>페이지를 찾을 수 없어요</h1>
        <p css={descriptionStyle}>요청하신 페이지가 없거나 이동되었어요.</p>
      </div>

      <button
        type="button"
        css={homeButtonStyle}
        onClick={() => navigate('/', { replace: true })}
      >
        돌아가기
      </button>
    </main>
  );
};

export default NotFoundPage;

const notFoundPageStyle = css`
  display: flex;
  flex-direction: column;
  justify-content: center;
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
  color: ${theme.colors.text.primary};
  font-size: 20px;
  font-weight: 700;
  line-height: 30px;
`;

const descriptionStyle = css`
  color: ${theme.colors.text.secondary};
  font-size: 14px;
  font-weight: 400;
  line-height: 22px;
  word-break: keep-all;
`;

const homeButtonStyle = css`
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
