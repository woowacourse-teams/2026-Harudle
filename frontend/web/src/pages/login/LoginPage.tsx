import { css } from '@emotion/react';
import harudleLogo from '../../assets/images/harudle-logo.png';
import loginHero from '../../assets/images/login-hero.png';
import kakaoIcon from '../../assets/icons/kakao.svg';
import { theme } from '../../styles/theme';
import { useEffect } from 'react';
import { useNavigate } from 'react-router';
import { restoreAccessToken } from '../../shared/auth';

const LoginPage = () => {
  const navigate = useNavigate();

  useEffect(() => {
    if (localStorage.getItem('harudle.has-completed-oauth') === null) {
      return;
    }

    const tryRestoreAccessToken = async (): Promise<boolean> => {
      try {
        await restoreAccessToken();
        return true;
      } catch {
        return false;
      }
    };

    const checkAuthentication = async () => {
      const isAuthenticated = await tryRestoreAccessToken();

      if (isAuthenticated) {
        navigate('/', { replace: true });
        return;
      }
    };

    void checkAuthentication();
  }, [navigate]);
  return (
    <div css={pageStyle}>
      <div css={heroStyle}>
        <img src={harudleLogo} alt="하루들 로고" css={logoStyle} />
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
          onClick={() => {
            window.location.assign('/oauth2/authorization/kakao');
          }}
        >
          <img src={kakaoIcon} alt="" css={kakaoIconStyle} />
          카카오로 시작하기
        </button>

        <p css={noticeStyle}>
          로그인하면 이용약관 및{' '}
          <a href="https://tecothon.notion.site/3c6d0505d9168025b01cdfa02d863a37?pvs=73">
            개인정보처리방침
          </a>
          에 동의한 것으로 간주됩니다.
        </p>
      </div>
    </div>
  );
};

export default LoginPage;

const pageStyle = css`
  display: flex;
  flex-direction: column;
  gap: 8px;
  width: 100%;
  height: 100%;
  padding: 12px 20px 10px;
  overflow: hidden;
  background-color: #ffffff;
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
  color: ${theme.colors.text.primary};
  font-size: 28px;
  font-weight: 700;
  line-height: 42px;
  text-align: center;
`;

const accentStyle = css`
  color: ${theme.colors.text.brand};
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
  color: ${theme.colors.text.primary};
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
  color: ${theme.colors.text.secondary};
  font-size: 11px;
  font-weight: 400;
  line-height: 18px;
  text-align: center;
`;
