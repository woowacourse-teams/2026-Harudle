import { css } from '@emotion/react';
import { type JSX } from 'react';
import { Navigate, Outlet, useLocation } from 'react-router';
import LoadingSpinner from './shared/LoadingSpinner';
import ActionButton from './shared/ActionButton';
import useEntryStatus from './useEntryStatus';

const EntryRoute = (): JSX.Element => {
  const { status, retry } = useEntryStatus();
  const { pathname } = useLocation();

  if (status === 'restoringSession') return <LoadingSpinner />;

  if (status === 'error') {
    return (
      <main css={feedbackStyle} role="alert">
        <p>로그인 상태를 확인하지 못했어요. 다시 시도해 주세요.</p>
        <ActionButton label="다시 시도" onClick={retry} />
      </main>
    );
  }

  if (pathname === '/') {
    if (status === 'landing') return <Navigate to="/landing" replace />;
    if (status === 'login') return <Navigate to="/login" replace />;
  }

  if (pathname === '/login' && status === 'home') {
    return <Navigate to="/" replace />;
  }

  return <Outlet />;
};

export default EntryRoute;

const feedbackStyle = css`
  display: flex;
  flex-direction: column;
  justify-content: center;
  align-items: center;
  gap: 16px;
  height: 100%;
  padding: 24px 20px;
  text-align: center;
`;
