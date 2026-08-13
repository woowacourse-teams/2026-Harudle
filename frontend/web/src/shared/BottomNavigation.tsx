import { css } from '@emotion/react';
import homeIcon from '../assets/icons/home.svg';
import settingsIcon from '../assets/icons/settings.svg';
import { theme } from '../styles/theme';
import { useLocation, useNavigate } from 'react-router';

const BottomNavigation = () => {
  const navigate = useNavigate();
  const { pathname } = useLocation();
  const isHomeActive = pathname === '/';
  const isSettingActive = pathname === '/setting';

  return (
    <nav css={bottomNavigationStyle}>
      <button
        type="button"
        css={navigationItemStyle}
        onClick={() => navigate('/')}
      >
        <span css={navigationIconStyle(homeIcon, isHomeActive, 32)} />
        <span css={navigationLabelStyle(isHomeActive)}>홈</span>
      </button>

      <button
        type="button"
        css={navigationItemStyle}
        onClick={() => navigate('/setting')}
      >
        <span css={navigationIconStyle(settingsIcon, isSettingActive, 28)} />
        <span css={navigationLabelStyle(isSettingActive)}>설정</span>
      </button>
    </nav>
  );
};

export default BottomNavigation;

const bottomNavigationStyle = css`
  display: flex;
  flex-shrink: 0;
  width: 100%;
  height: 88px;
  background-color: ${theme.colors.background};
  box-shadow: 0 -1px 2px rgba(17, 17, 24, 0.04);
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

const navigationIconStyle = (
  icon: string,
  isActive: boolean,
  size: number,
) => css`
  width: ${size}px;
  height: ${size}px;
  background-color: ${
    isActive ? theme.colors.primary : theme.colors.textSecondary
  };
  mask: url(${icon}) center / contain no-repeat;
`;

const navigationLabelStyle = (isActive: boolean) => css`
  color: ${isActive ? theme.colors.accent : theme.colors.textSecondary};
  font-family: 'Noto Sans KR', sans-serif;
  font-size: 12px;
  font-weight: ${isActive ? 700 : 400};
  line-height: 18px;
`;
