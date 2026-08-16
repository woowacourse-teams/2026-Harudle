import { css } from '@emotion/react';
import BottomNavigation from '../../shared/BottomNavigation';
import harudleLogo from '../../assets/images/harudle-logo.png';
import { useNavigate } from 'react-router';

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
        <div>설정</div>
        <div>
          <div>계정</div>
          <div>
            <span>이름</span>
            <span>정이현</span>
          </div>
          <div>
            <span>소셜 계정</span>
            <span>Kakao</span>
          </div>
        </div>

        <button>로그아웃</button>
      </main>

      <BottomNavigation />
    </div>
  );
};

export default SettingPage;

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
  flex: 1;
  overflow-y: auto;
  padding: 20px;
`;
