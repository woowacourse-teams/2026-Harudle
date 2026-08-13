import { css } from '@emotion/react';
import { useEffect } from 'react';
import { useNavigate, useSearchParams } from 'react-router';
import loadingAnimation from '../../assets/images/loading-animation.webp';
import { restoreLogin } from '../../shared/auth';
import { useDelayedLoading } from '../../shared/useDelayedLoading';

const AuthCallbackPage = () => {
  const navigate = useNavigate();
  const [searchParams] = useSearchParams();
  const showLoading = useDelayedLoading(true);

  useEffect(() => {
    if (searchParams.has('error')) {
      navigate('/login', { replace: true });
      return;
    }

    void restoreLogin().then((isAuthenticated) => {
      navigate(isAuthenticated ? '/' : '/login', { replace: true });
    });
  }, [navigate, searchParams]);

  return (
    <div css={loadingStyle}>
      {showLoading && (
        <img src={loadingAnimation} alt="로그인 처리 중" css={imageStyle} />
      )}
    </div>
  );
};

export default AuthCallbackPage;

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
