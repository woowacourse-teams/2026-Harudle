import { css } from '@emotion/react';
import harudleLogo from '../../assets/images/harudle-logo.png';
import loginHero from '../../assets/images/login-shared-comic.png';
import kakaoIcon from '../../assets/icons/kakao.svg';
import { theme } from '../../styles/theme';
import { useNavigate } from 'react-router';
import { isMswEnabled } from '../../shared/environment';
import { LOGIN_COPY } from './copy';

const LoginPage = () => {
  const navigate = useNavigate();

  const handleKakaoLogin = () => {
    if (isMswEnabled) {
      navigate('/auth/callback');
      return;
    }

    window.location.assign('/oauth2/authorization/kakao');
  };

  return (
    <div css={pageStyle}>
      <div css={topSpaceStyle} aria-hidden="true" />
      <div css={logoFrameStyle}>
        <img src={harudleLogo} alt="하루들 로고" css={logoStyle} />
      </div>
      <div css={brandSpaceStyle} aria-hidden="true" />
      <div css={heroImageFrameStyle}>
        <img
          src={loginHero}
          alt="네컷만화를 함께 들고 웃는 하루들 캐릭터와 강아지"
          css={heroImageStyle}
        />
      </div>

      <div css={storySpaceStyle} aria-hidden="true" />
      <h1 css={titleStyle}>
        {LOGIN_COPY.taglineLines[0]}
        <br />
        <span css={accentStyle}>{LOGIN_COPY.taglineLines[1]}</span>
      </h1>

      <div css={actionSpaceStyle} aria-hidden="true" />
      <div css={loginAreaStyle}>
        <button type="button" css={kakaoButtonStyle} onClick={handleKakaoLogin}>
          <img src={kakaoIcon} alt="" css={kakaoIconStyle} />
          카카오로 시작하기
        </button>

        <p css={noticeStyle}>
          로그인하면 이용약관 및{' '}
          <a href="https://harudle.notion.site/">개인정보처리방침</a>에 동의한
          것으로 간주됩니다.
        </p>
      </div>
    </div>
  );
};

export default LoginPage;

const pageStyle = css`
  display: flex;
  flex-direction: column;
  align-items: center;
  width: 100%;
  height: 100%;
  padding: 16px 20px 20px;
  overflow-y: auto;
  background-color: ${theme.colors.background.surface};
`;

const topSpaceStyle = css`
  flex: 0.8 0 12px;
`;

const brandSpaceStyle = css`
  flex: 1 0 24px;
`;

const storySpaceStyle = css`
  flex: 0.5 0 16px;
`;

const actionSpaceStyle = css`
  flex: 0.65 0 24px;
`;

// 이미지 안의 투명 여백을 제외해 보이는 콘텐츠 사이로 간격을 맞춘다.
const logoFrameStyle = css`
  flex-shrink: 0;
  width: min(246px, 100%, 30dvh);
  aspect-ratio: 1536 / 560;
  overflow: hidden;
`;

const logoStyle = css`
  display: block;
  width: 100%;
  height: 100%;
  object-fit: cover;
`;

const heroImageFrameStyle = css`
  flex-shrink: 0;
  width: min(336px, 100%, 46dvh);
  aspect-ratio: 1536 / 768;
  overflow: hidden;
`;

const heroImageStyle = css`
  display: block;
  width: 100%;
  height: 100%;
  object-fit: cover;
  object-position: center 62.5%;
`;

const titleStyle = css`
  flex-shrink: 0;
  width: 100%;
  color: ${theme.colors.foreground.neutral};
  font-size: 26px;
  font-weight: 700;
  line-height: 1.3;
  letter-spacing: -0.02em;
  text-align: center;
  word-break: keep-all;
`;

const accentStyle = css`
  color: ${theme.colors.foreground.brand};
  font-size: 30px;
`;

const loginAreaStyle = css`
  display: flex;
  flex-shrink: 0;
  flex-direction: column;
  align-items: center;
  gap: 10px;
  width: 100%;
`;

const kakaoButtonStyle = css`
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 10px;
  width: 100%;
  max-width: 342px;
  height: 56px;
  padding: 0 24px;
  border: none;
  border-radius: 16px;
  background-color: ${theme.colors.background.kakao};
  box-shadow: 0 4px 16px rgb(26 20 41 / 6%);
  color: ${theme.colors.foreground.neutral};
  font-size: 17px;
  font-weight: 700;
  line-height: 24px;
  cursor: pointer;
`;

const kakaoIconStyle = css`
  width: 24px;
  height: 24px;
`;

const noticeStyle = css`
  width: 100%;
  max-width: 342px;
  color: ${theme.colors.foreground.neutralMuted};
  font-size: 12px;
  font-weight: 400;
  line-height: 18px;
  text-align: center;
`;
