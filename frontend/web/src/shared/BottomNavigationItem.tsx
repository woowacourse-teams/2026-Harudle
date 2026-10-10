import { css } from '@emotion/react';
import { theme } from '../styles/theme';
import { useLocation, useNavigate } from 'react-router';
import type { NavigationItem } from './BottomNavigation';

type BottomNavigationItemProps = NavigationItem;

const BottomNavigationItem = ({
  path,
  iconSrc,
  label,
}: BottomNavigationItemProps) => {
  const { pathname: nowPath } = useLocation();
  const navigate = useNavigate();

  return (
    <button css={buttonStyle} onClick={() => navigate(path)}>
      <span css={iconStyle(iconSrc, nowPath === path)} />
      <span css={labelStyle(nowPath === path)}>{label}</span>
    </button>
  );
};

export default BottomNavigationItem;

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
  background-color: ${isActive ? theme.colors.foreground.brand : theme.colors.foreground.neutralMuted};
  -webkit-mask: url(${icon}) center / contain no-repeat;
  mask: url(${icon}) center / contain no-repeat;
`;

const labelStyle = (isActive: boolean) => css`
  color: ${isActive ? theme.colors.foreground.brand : theme.colors.foreground.neutralMuted};
`;
