import { css } from '@emotion/react';
import { useRef } from 'react';
import { theme } from '../styles/theme';
import { useLocation, useNavigate } from 'react-router';
import type { BottomSheetHandle } from './BottomSheet';
import NeedLoginBottomSheet from './NeedLoginBottomSheet';
import { NEED_LOGIN_COPY } from './needLoginCopy';
import type { NavigationItem } from './BottomNavigation';

const BottomNavigationItem = ({ path, iconSrc, label }: NavigationItem) => {
  const { pathname: nowPath } = useLocation();
  const navigate = useNavigate();
  const loginSheetRef = useRef<BottomSheetHandle>(null);

  const handleClick = (): void => {
    if (
      (path === '/album' || path === '/setting') &&
      localStorage.getItem('harudle.has-completed-oauth') === null
    ) {
      loginSheetRef.current?.open();
      return;
    }
    void navigate(path);
  };

  return (
    <>
      <button type="button" css={buttonStyle} onClick={handleClick}>
        <span css={iconStyle(iconSrc, nowPath === path)} />
        <span css={labelStyle(nowPath === path)}>{label}</span>
      </button>
      <NeedLoginBottomSheet
        ref={loginSheetRef}
        description={NEED_LOGIN_COPY.navigationDescription}
      />
    </>
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
