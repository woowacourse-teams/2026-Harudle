import { css } from '@emotion/react';
import type { ReactNode } from 'react';
import { useNavigate } from 'react-router';
import harudleLogo from '../assets/images/harudle-logo.webp';
import BottomNavigation from './BottomNavigation';

interface BottomNavigationLayoutProps {
  readonly children: ReactNode;
}

const BottomNavigationLayout = ({ children }: BottomNavigationLayoutProps) => {
  const navigate = useNavigate();

  return (
    <div css={layoutStyle}>
      <header css={pageHeaderStyle}>
        <button
          type="button"
          css={logoButtonStyle}
          onClick={(): void => {
            void navigate('/');
          }}
        >
          <img css={logoStyle} src={harudleLogo} alt="하루들" />
        </button>
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
  width: 100%;
  height: 56px;
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
