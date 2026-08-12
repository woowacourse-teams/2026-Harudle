import BottomNavigation from '../../shared/BottomNavigation';
import { css } from '@emotion/react';
import { theme } from '../../styles/theme';
import harudleLogo from '../../assets/images/harudle-logo.png';
import { useNavigate } from 'react-router';

const SettingPage = () => {
  const navigate = useNavigate();

  return (
    <div css={settingPageStyle}>
      <header css={pageHeaderStyle}>
        <button css={logoButtonStyle} onClick={() => navigate('/')}>
          <img src={harudleLogo} alt="하루들" css={logoStyle} />
        </button>
      </header>

      <main css={settingContentStyle}>
        <div css={pageTitleStyle}>설정</div>

        <div css={accountCardStyle}>
          <div css={settingRowStyle}>
            <span css={settingLabelStyle}>이름</span>
            <span css={settingValueStyle}>
              <span>정이현</span>
            </span>
          </div>

          <div css={settingRowStyle}>
            <span css={settingLabelStyle}>소셜 계정</span>
            <span css={settingValueStyle}>
              <span>Kakao</span>
            </span>
          </div>
        </div>

        <button type="button" css={logoutButtonStyle}>
          로그아웃
        </button>
      </main>

      <BottomNavigation />
    </div>
  );
};

export default SettingPage;

const settingPageStyle = css`
  display: flex;
  flex-direction: column;
  align-items: center;
  width: 100%;
  height: 100%;
  overflow: hidden;
  background-color: ${theme.colors.background};
`;

const pageHeaderStyle = css`
  width: 100%;
  height: 71px;
  padding: 0 20px;
  box-sizing: border-box;
  overflow: hidden;
`;

const logoButtonStyle = css`
  display: flex;
  align-items: center;
  width: 100%;
  height: 71px;
  border: none;
  background-color: transparent;
  cursor: pointer;
`;

const logoStyle = css`
  width: 106px;
  height: 71px;
`;

const settingContentStyle = css`
  display: flex;
  flex: 1;
  flex-direction: column;
  gap: 10px;
  width: 342px;
  overflow: hidden;
`;

const pageTitleStyle = css`
  color: ${theme.colors.textPrimary};
  font-family: 'Noto Sans KR', sans-serif;
  font-size: 22px;
  font-weight: 700;
  line-height: 32px;
`;

const accountCardStyle = css`
  width: 100%;
  height: 120px;
  overflow: hidden;
  border: 1px solid ${theme.colors.border};
  border-radius: 16px;
  background-color: ${theme.colors.background};
  box-sizing: border-box;
`;

const settingRowStyle = css`
  display: flex;
  align-items: center;
  justify-content: space-between;
  width: 100%;
  height: 60px;
  padding: 0 12px 0 16px;
  box-sizing: border-box;

  &:first-of-type {
    border-bottom: 1px solid ${theme.colors.border};
  }
`;

const settingLabelStyle = css`
  color: ${theme.colors.textPrimary};
  font-family: 'Noto Sans KR', sans-serif;
  font-size: 15px;
  font-weight: 400;
  line-height: 24px;
`;

const settingValueStyle = css`
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: 8px;
  width: 150px;
  height: 32px;
  color: ${theme.colors.textSecondary};
  font-family: 'Noto Sans KR', sans-serif;
  font-size: 15px;
  font-weight: 400;
  line-height: 24px;
  text-align: right;
  text-transform: capitalize;
`;

const logoutButtonStyle = css`
  width: 100%;
  height: 56px;
  padding: 0 16px;
  border: 1px solid ${theme.colors.border};
  border-radius: 16px;
  background-color: ${theme.colors.background};
  color: ${theme.colors.danger};
  font-family: 'Noto Sans KR', sans-serif;
  font-size: 15px;
  font-weight: 500;
  line-height: 24px;
  text-align: left;
  box-sizing: border-box;
  cursor: pointer;

  &:active {
    background-color: #fff7f7;
  }
`;
