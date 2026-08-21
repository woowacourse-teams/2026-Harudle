import { css } from '@emotion/react';
import { useLocation, useNavigate } from 'react-router';
import homeIcon from '../assets/icons/home.svg';
import settingIcon from '../assets/icons/settings.svg';
import { theme } from '../styles/theme';

const BottomNavigation = () => {
  const { pathname } = useLocation();
  const navigate = useNavigate();

  return (
    <nav css={bottomNavigationStyle}>
      <button css={buttonStyle} onClick={() => navigate('/')}>
        <span css={iconStyle(homeIcon, pathname === '/')} />
        <span css={labelStyle(pathname === '/')}>홈</span>
      </button>
      <button css={buttonStyle} onClick={() => navigate('/setting')}>
        <span css={iconStyle(settingIcon, pathname === '/setting')} />
        <span css={labelStyle(pathname === '/setting')}>설정</span>
      </button>
    </nav>
  );
};

export default BottomNavigation;

const bottomNavigationStyle = css`
  display: flex;
  justify-content: space-around;
  left: 0;
  right: 0;
  bottom: 0;
  width: 100%;
  height: 80px;
  background-color: #ffffff;
  box-shadow: 0 -1px 2px rgba(17, 17, 24, 0.04);
`;

const buttonStyle = css`
  flex: 1;
  display: flex;
  flex-direction: column;
  justify-content: center;
  align-items: center;
  height: 100%;
  border: none;
  background: none;
  cursor: pointer;

  &:active {
    background-color: #f5f3fa;
  }
`;

const iconStyle = (icon: string, isActive: boolean) => css`
  display: block;
  width: 32px;
  height: 32px;
  background-color: ${isActive ? theme.colors.bg.brand : '#6F6B79'};
  -webkit-mask: url(${icon}) center / contain no-repeat;
  mask: url(${icon}) center / contain no-repeat;
`;

const labelStyle = (isActive: boolean) => css`
  color: ${isActive ? theme.colors.bg.brand : '#6F6B79'};
`;
