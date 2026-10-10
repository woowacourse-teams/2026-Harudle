import { css } from '@emotion/react';
import fourCutAlbumIcon from '../assets/icons/four-cut-album.svg';
import homeIcon from '../assets/icons/home.svg';
import settingIcon from '../assets/icons/settings.svg';
import { theme } from '../styles/theme';
import BottomNavigationItem from './BottomNavigationItem';

export interface NavigationItem {
  readonly path: string;
  readonly iconSrc: string;
  readonly label: string;
}

const BottomNavigation = () => {
  const navigationItemList: NavigationItem[] = [
    {
      path: '/',
      iconSrc: homeIcon,
      label: '홈',
    },
    {
      path: '/album',
      iconSrc: fourCutAlbumIcon,
      label: '네컷 모아보기',
    },
    {
      path: '/setting',
      iconSrc: settingIcon,
      label: '설정',
    },
  ];

  return (
    <nav css={bottomNavigationStyle}>
      {navigationItemList.map((item) => (
        <BottomNavigationItem {...item} />
      ))}
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
  height: 64px;
  background-color: ${theme.colors.background.surface};
  box-shadow: 0 -1px 2px rgba(17, 17, 24, 0.04);
`;
