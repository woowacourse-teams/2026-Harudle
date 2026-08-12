import { css } from '@emotion/react';
import homeIcon from '../assets/icons/home.svg';
import settingsIcon from '../assets/icons/settings.svg';
import { theme } from '../styles/theme';
import { useNavigate } from 'react-router';

const BottomNavigation = () => {
  const navigate = useNavigate();
  return (
    <nav css={bottomNavigationStyle}>
      <button
        type="button"
        css={navigationItemStyle}
        onClick={() => navigate('/')}
      >
        <img src={homeIcon} alt="홈 페이지 이동 버튼" css={homeIconStyle} />
        <span css={activeLabelStyle}>홈</span>
      </button>

      <button
        type="button"
        css={navigationItemStyle}
        onClick={() => navigate('/setting')}
      >
        <img
          src={settingsIcon}
          alt="설정 페이지 이동 버튼"
          css={settingsIconStyle}
        />
        <span css={inactiveLabelStyle}>설정</span>
      </button>
    </nav>
  );
};

export default BottomNavigation;

const bottomNavigationStyle = css`
  display: flex;
  width: 100%;
  height: 88px;
  background-color: ${theme.colors.background};
`;

const navigationItemStyle = css`
  display: flex;
  flex: 1;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 4px;
  padding: 0;
  border: none;
  background-color: transparent;
  cursor: pointer;

  &:active {
    background-color: #f5f3fa;
  }
`;

const homeIconStyle = css`
  width: 32px;
  height: 32px;
`;

const settingsIconStyle = css`
  width: 32px;
  height: 32px;
`;

const activeLabelStyle = css`
  color: ${theme.colors.accent};
  font-size: 12px;
  font-weight: 700;
  line-height: 18px;
`;

const inactiveLabelStyle = css`
  color: ${theme.colors.textSecondary};
  font-size: 12px;
  font-weight: 400;
  line-height: 18px;
`;
