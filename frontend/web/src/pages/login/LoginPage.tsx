import { css } from '@emotion/react';
import harudleLogo from '../../assets/images/harudle-logo.png';
import loginHero from '../../assets/images/login-hero.png';
import kakaoIcon from '../../assets/icons/kakao.svg';
import { theme } from '../../styles/theme';

const LoginPage = () => {
  const handleKakaoLogin = async () => {
    const loginUrl = '/oauth2/authorization/kakao';

    if (process.env.NODE_ENV !== 'development') {
      window.location.assign(loginUrl);
      return;
    }

    const response = await fetch(loginUrl);

    if (!response.ok) {
      throw new Error('로그인 요청에 실패했습니다.');
    }

    sessionStorage.setItem('mockLoggedIn', 'true');
    window.location.assign(response.url);
  };

  return (
    <main css={loginPageStyle}>
      <div css={heroStyle}>
        <img src={harudleLogo} alt="하루들" css={logoStyle} />
        <img src={loginHero} alt="하루들 캐릭터" css={heroImageStyle} />

        <h1 css={titleStyle}>
          오늘의 하루,
          <br />
          <span css={accentStyle}>네컷 만화</span>로 남겨요
        </h1>
      </div>

      <div css={loginAreaStyle}>
        <button
          type="button"
          css={kakaoButtonStyle}
          onClick={() => void handleKakaoLogin()}
        >
          <img src={kakaoIcon} alt="" css={kakaoIconStyle} />
          카카오로 시작하기
        </button>

        <p css={noticeStyle}>
          로그인하면 이용약관 및 개인정보처리방침에 동의한 것으로 간주됩니다.
        </p>
      </div>
    </main>
  );
};

export default LoginPage;

const loginPageStyle = css`
  display: flex;
  flex-direction: column;
  gap: 8px;
  width: 100%;
  height: 100%;
  padding: 12px 20px 10px;
  overflow: hidden;
  background-color: ${theme.colors.background};
  box-sizing: border-box;
`;

const heroStyle = css`
  display: flex;
  flex: 1;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 18px;
  min-height: 0;
  overflow: hidden;
`;

const logoStyle = css`
  width: 276px;
  height: 184px;
  object-fit: contain;
`;

const heroImageStyle = css`
  width: 291px;
  height: 194px;
  object-fit: contain;
`;

const titleStyle = css`
  color: ${theme.colors.textPrimary};
  font-family: 'Noto Sans KR', sans-serif;
  font-size: 28px;
  font-weight: 700;
  line-height: 42px;
  text-align: center;
`;

const accentStyle = css`
  color: ${theme.colors.accent};
`;

const loginAreaStyle = css`
  display: flex;
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
  background-color: #ffd66b;
  box-shadow: 0 4px 16px rgb(26 20 41 / 6%);
  color: ${theme.colors.textPrimary};
  font-family: 'Noto Sans KR', sans-serif;
  font-size: 17px;
  font-weight: 700;
  line-height: 24px;
  cursor: pointer;
  box-sizing: border-box;
`;

const kakaoIconStyle = css`
  width: 24px;
  height: 24px;
`;

const noticeStyle = css`
  width: 100%;
  max-width: 342px;
  color: ${theme.colors.textSecondary};
  font-family: 'Noto Sans KR', sans-serif;
  font-size: 11px;
  font-weight: 400;
  line-height: 18px;
  text-align: center;
`;
