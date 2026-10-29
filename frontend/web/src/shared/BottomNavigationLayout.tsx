import { css } from '@emotion/react';
import type { ReactNode } from 'react';
import { useNavigate } from 'react-router';
import harudleLogo from '../assets/images/harudle-logo.webp';
import BottomNavigation from './BottomNavigation';

interface BottomNavigationLayoutProps {
  readonly children: ReactNode;
  readonly headerLeft?: ReactNode;
  readonly headerRight?: ReactNode;
}

const BottomNavigationLayout = ({
  children,
  headerLeft,
  headerRight,
}: BottomNavigationLayoutProps) => {
  const navigate = useNavigate();

  return (
    <div css={layoutStyle}>
      <header css={pageHeaderStyle}>
        {headerLeft && <div css={headerLeftStyle}>{headerLeft}</div>}
        <button
          type="button"
          css={logoButtonStyle}
          onClick={(): void => {
            void navigate('/');
          }}
        >
          <img css={logoStyle} src={harudleLogo} alt="하루들" />
        </button>
        {headerRight && <div css={headerRightStyle}>{headerRight}</div>}
      </header>

      {children}

      <BottomNavigation />
    </div>
  );
};

export default BottomNavigationLayout;

const layoutStyle = css`
  display: flex;
  flex-direction: column;
  height: 100%;
`;

const pageHeaderStyle = css`
  position: relative;
  width: 100%;
  height: 56px;
`;

const logoButtonStyle = css`
  display: flex;
  justify-content: center;
  align-items: center;
  width: fit-content;
  height: 100%;
  margin: 0 auto;
  border: none;
  background: none;
  cursor: pointer;
`;

const logoStyle = css`
  width: 106px;
  height: 71px;
`;

const headerRightStyle = css`
  position: absolute;
  top: 0;
  right: 20px;
  display: flex;
  align-items: center;
  height: 100%;
`;

const headerLeftStyle = css`
  position: absolute;
  top: 0;
  left: 20px;
  display: flex;
  align-items: center;
  height: 100%;
`;
