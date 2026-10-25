import { css } from '@emotion/react';
import { useEffect, useState } from 'react';
import { Link, Navigate, Outlet, useLocation } from 'react-router';
import {
  getAgreementStatus,
  type AgreementStatus,
} from './domain/legal/agreements';
import { RequestError, type ApiRequest } from './shared/api';
import { TERMS_ACCEPTANCE_REQUIRED_EVENT } from './shared/auth';
import ActionButton from './shared/ActionButton';
import DiaryGenerationNotice from './shared/DiaryGenerationNotice';
import LoadingSpinner from './shared/LoadingSpinner';
import { theme } from './styles/theme';
import { CONSENT_COPY } from './pages/consent/copy';
import { LOGIN_COPY } from './pages/login/copy';

const AgreementRoute = () => {
  const location = useLocation();
  const returnTo = `${location.pathname}${location.search}${location.hash}`;
  return <AgreementCheck key={location.pathname} returnTo={returnTo} />;
};

const AgreementCheck = ({ returnTo }: { readonly returnTo: string }) => {
  const [request, setRequest] = useState<ApiRequest<AgreementStatus | null>>({
    status: 'loading',
  });
  const [attempt, setAttempt] = useState(0);

  useEffect((): (() => void) => {
    const controller = new AbortController();
    let canRecheck = false;
    const recheck = (): void => {
      if (!canRecheck) return;
      canRecheck = false;
      setRequest({ status: 'loading' });
      setAttempt((current): number => current + 1);
    };
    window.addEventListener(TERMS_ACCEPTANCE_REQUIRED_EVENT, recheck);
    const execute = async (): Promise<void> => {
      setRequest({ status: 'loading' });
      let hasLoginHistory = false;
      try {
        hasLoginHistory =
          localStorage.getItem('harudle.has-completed-oauth') !== null;
      } catch {
        /* 저장소 표시는 조회 여부의 힌트로만 사용하며 실제 회원 권한은 서버가 검사한다. */
      }
      if (!hasLoginHistory) {
        setRequest({ status: 'success', data: null });
        return;
      }
      try {
        const status = await getAgreementStatus({ signal: controller.signal });
        if (!controller.signal.aborted) {
          canRecheck = !status.requiresAcceptance;
          setRequest({ status: 'success', data: status });
        }
      } catch (error: unknown) {
        if (!controller.signal.aborted)
          setRequest({
            status: 'error',
            error:
              error instanceof Error
                ? error
                : new Error(CONSENT_COPY.loadError),
          });
      }
    };
    void execute();
    return (): void => {
      canRecheck = false;
      controller.abort();
      window.removeEventListener(TERMS_ACCEPTANCE_REQUIRED_EVENT, recheck);
    };
  }, [attempt]);

  if (request.status === 'idle' || request.status === 'loading')
    return <LoadingSpinner />;
  if (request.status === 'error') {
    const suspended =
      request.error instanceof RequestError &&
      request.error.problem.code === 'ACCOUNT_SUSPENDED';
    return (
      <main css={feedbackStyle}>
        <div role="alert" css={errorStyle}>
          <p>
            {suspended ? LOGIN_COPY.suspendedAccount : CONSENT_COPY.loadError}
          </p>
          {!suspended && (
            <ActionButton
              label={CONSENT_COPY.retry}
              variant="secondary"
              onClick={(): void => setAttempt((current): number => current + 1)}
            />
          )}
          <Link to="/consent" state={{ returnTo }}>
            {suspended ? CONSENT_COPY.accountActions : CONSENT_COPY.title}
          </Link>
        </div>
      </main>
    );
  }
  if (request.data?.requiresAcceptance)
    return <Navigate to="/consent" replace state={{ returnTo }} />;
  return (
    <>
      {request.data !== null && <DiaryGenerationNotice />}
      <Outlet />
    </>
  );
};

export default AgreementRoute;

const feedbackStyle = css`
  display: flex;
  align-items: center;
  justify-content: center;
  height: 100%;
  padding: 24px 20px;
`;
const errorStyle = css`
  display: grid;
  gap: 16px;
  width: min(100%, 280px);
  text-align: center;
  color: ${theme.colors.foreground.neutralMuted};
  font-size: 14px;
  line-height: 22px;
  a {
    color: ${theme.colors.foreground.brand};
    text-underline-offset: 3px;
  }
`;
