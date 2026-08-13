import { css } from '@emotion/react';
import { useEffect, useState } from 'react';
import { Navigate, Outlet } from 'react-router';
import loadingAnimation from '../assets/images/loading-animation.webp';
import { restoreLogin } from './auth';
import { useDelayedLoading } from './useDelayedLoading';

const AuthGuard = () => {
  const [isAuthenticated, setIsAuthenticated] = useState<boolean | null>(null);
  const showLoading = useDelayedLoading(isAuthenticated === null);

  useEffect(() => {
    let isMounted = true;

    void restoreLogin().then((result) => {
      if (isMounted) {
        setIsAuthenticated(result);
      }
    });

    return () => {
      isMounted = false;
    };
  }, []);

  if (isAuthenticated === null) {
    return (
      <div css={loadingStyle}>
        {showLoading && (
          <img src={loadingAnimation} alt="로그인 확인 중" css={imageStyle} />
        )}
      </div>
    );
  }

  if (!isAuthenticated) {
    return <Navigate to="/login" replace />;
  }

  return <Outlet />;
};

export default AuthGuard;

const loadingStyle = css`
  display: flex;
  align-items: center;
  justify-content: center;
  width: 100%;
  height: 100%;
`;

const imageStyle = css`
  width: 160px;
  height: 160px;
`;
