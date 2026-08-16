import { css } from '@emotion/react';
import BottomNavigation from '../../shared/BottomNavigation';
import harudleLogo from '../../assets/images/harudle-logo.png';
import { useNavigate } from 'react-router';
import useProfile from './useProfile';
import loadingAnimation from '../../assets/images/loading-animation.webp';
import { theme } from '../../styles/theme';
import useLogout from './useLogout';

const SettingPage = () => {
  const navigate = useNavigate();

  return (
    <div css={pageStyle}>
      <header css={pageHeaderStyle}>
        <button css={logoButtonStyle} onClick={() => navigate('/')}>
          <img css={logoStyle} src={harudleLogo} alt="하루들" />
        </button>
      </header>

      <main css={contentStyle}>
        <div css={pageTitleStyle}>설정</div>
        <SettingPageContent />
      </main>

      <BottomNavigation />
    </div>
  );
};

export default SettingPage;

const SettingPageContent = () => {
  const { profileRequest } = useProfile();
  const { logoutRequest, handleLogout } = useLogout();

  if (profileRequest.status === 'idle' || profileRequest.status === 'loading') {
    return (
      <div css={loadingAnimationBoxStyle}>
        <img src={loadingAnimation} alt="로딩 중" css={loadingImageStyle} />
      </div>
    );
  }

  if (profileRequest.status === 'error') {
    return <div>{profileRequest.error.message}</div>;
  }

  const { name, oauthProviders } = profileRequest.data;

  return (
    <div css={settingPageContentStyle}>
      <div css={accountCardStyle}>
        <div css={settingRowStyle}>
          <span css={settingLabelStyle}>이름</span>
          <span css={settingValueStyle}>
            <span>{name}</span>
          </span>
        </div>
        <div css={settingRowStyle}>
          <span css={settingLabelStyle}>소셜 계정</span>
          <span css={settingValueStyle}>
            <span>{oauthProviders.join(', ')}</span>
          </span>
        </div>
      </div>

      <button
        type="button"
        css={logoutButtonStyle}
        disabled={logoutRequest.status === 'loading'}
        onClick={() => void handleLogout()}
      >
        로그아웃
      </button>
      {logoutRequest.status === 'error' && (
        <div css={logoutErrorStyle}>{logoutRequest.error.message}</div>
      )}
    </div>
  );
};

const loadingAnimationBoxStyle = css`
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

const pageStyle = css`
  display: flex;
  flex-direction: column;
  height: 100%;
`;

const pageHeaderStyle = css`
  width: 100%;
  height: 71px;
  box-sizing: border-box;
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

const contentStyle = css`
  position: relative;
  display: flex;
  flex: 1;
  flex-direction: column;
  gap: 8px;
  min-height: 0px;
  padding: 20px 20px 0 20px;
`;

const pageTitleStyle = css`
  color: ${theme.colors.text.primary};
  font-size: 22px;
  font-weight: 700;
  line-height: 32px;
`;

const settingPageContentStyle = css`
  flex: 1;
  display: flex;
  flex-direction: column;
  gap: 10px;
  min-height: 0;
  overflow-y: auto;
`;

const accountCardStyle = css`
  height: 120px;
  overflow: hidden;
  border: 1px solid ${theme.colors.border.primary};
  border-radius: 16px;
`;

const settingRowStyle = css`
  display: flex;
  align-items: center;
  justify-content: space-between;
  height: 60px;
  padding: 0 16px;
  font-size: 15px;
  line-height: 24px;

  &:first-of-type {
    border-bottom: 1px solid ${theme.colors.border.primary};
  }
`;

const settingLabelStyle = css`
  color: ${theme.colors.text.primary};
`;

const settingValueStyle = css`
  color: ${theme.colors.text.secondary};
  text-transform: capitalize;
`;

const logoutButtonStyle = css`
  width: 100%;
  height: 56px;
  padding: 0 16px;
  border: 1px solid ${theme.colors.border.primary};
  border-radius: 16px;
  background-color: transparent;
  color: ${theme.colors.text.danger};
  font-size: 15px;
  font-weight: 500;
  line-height: 24px;
  text-align: left;
  cursor: pointer;

  &:active {
    background-color: #fff7f7;
  }

  &:disabled {
    opacity: 0.5;
    cursor: default;
  }
`;

const logoutErrorStyle = css`
  color: ${theme.colors.text.danger};
  font-size: 13px;
  line-height: 20px;
`;
