import { css } from '@emotion/react';
import {
  useEffect,
  useRef,
  useState,
  type FormEvent,
  type MouseEvent,
} from 'react';
import { Link, useLocation, useNavigate } from 'react-router';
import { acceptTerms, getAgreementStatus } from '../../domain/legal/agreements';
import {
  getLegalDocuments,
  type LegalDocument,
} from '../../domain/legal/legalDocuments';
import { logout } from '../../domain/user/logout';
import { removeGenerationId } from '../../domain/diary/diaryGenerationStore';
import { DIARY_GENERATION_TOAST_ID_KEY } from '../../shared/constants';
import { useAnalytics } from '../../posthog/useAnalytics';
import { RequestError, type ApiRequest } from '../../shared/api';
import {
  AuthenticationRequiredError,
  requestCsrfToken,
  restoreAccessToken,
  setAccessToken,
} from '../../shared/auth';
import ActionButton from '../../shared/ActionButton';
import LoadingSpinner from '../../shared/LoadingSpinner';
import { theme } from '../../styles/theme';
import logoutIcon from '../../assets/icons/logout.svg';
import checkIcon from '../../assets/icons/check.svg';
import chevronIcon from '../../assets/icons/chevron-right.svg';
import harudleLogo from '../../assets/images/harudle-logo.webp';
import { CONSENT_COPY } from './copy';
import { getReturnPath } from './returnPath';
import { LOGIN_COPY } from '../login/copy';

interface AgreementForm {
  readonly terms: LegalDocument;
  readonly privacy: LegalDocument;
}

const isLoginRequired = (error: unknown): boolean =>
  error instanceof AuthenticationRequiredError ||
  (error instanceof RequestError &&
    (error.problem.status === 401 ||
      error.problem.code === 'INVALID_REFRESH_TOKEN'));

const ConsentPage = () => {
  const location = useLocation();
  const navigate = useNavigate();
  const { resetUser } = useAnalytics();
  const returnTo = getReturnPath(location.state);
  const [request, setRequest] = useState<ApiRequest<AgreementForm | null>>({
    status: 'loading',
  });
  const [mutation, setMutation] = useState<ApiRequest<void>>({
    status: 'idle',
  });
  const [mutationType, setMutationType] = useState<'accept' | 'logout'>(
    'accept',
  );
  const [termsChecked, setTermsChecked] = useState(false);
  const [ageChecked, setAgeChecked] = useState(false);
  const [attempt, setAttempt] = useState(0);
  const operation = useRef<AbortController | null>(null);
  const busy = useRef(false);
  const isMutating = mutation.status === 'loading';
  const allChecked = termsChecked && ageChecked;

  useEffect((): (() => void) => {
    const controller = new AbortController();
    operation.current = controller;
    busy.current = false;
    const execute = async (): Promise<void> => {
      setTermsChecked(false);
      setAgeChecked(false);
      setRequest({ status: 'loading' });
      try {
        await restoreAccessToken();
        if (controller.signal.aborted) return;
        const [catalog, status] = await Promise.all([
          getLegalDocuments({ signal: controller.signal }),
          getAgreementStatus({ signal: controller.signal }),
        ]);
        if (controller.signal.aborted) return;
        const terms = catalog.documents.find(
          (item): boolean => item.type === 'TERMS',
        );
        const privacy = catalog.documents.find(
          (item): boolean => item.type === 'PRIVACY',
        );
        if (
          !terms ||
          !privacy ||
          terms.version !== status.terms.version ||
          terms.contentHash !== status.terms.contentHash ||
          privacy.version !== status.privacyNotice.version ||
          privacy.contentHash !== status.privacyNotice.contentHash
        )
          throw new Error(CONSENT_COPY.changed);
        if (status.acceptedAt !== null && !status.requiresAcceptance) {
          void navigate(returnTo, { replace: true });
          return;
        }
        setRequest({ status: 'success', data: { terms, privacy } });
      } catch (error: unknown) {
        if (controller.signal.aborted) return;
        if (isLoginRequired(error)) {
          setRequest({ status: 'success', data: null });
          return;
        }
        setRequest({
          status: 'error',
          error:
            error instanceof Error ? error : new Error(CONSENT_COPY.loadError),
        });
      }
    };
    void execute();
    return (): void => controller.abort();
  }, [attempt, navigate, returnTo]);

  const handleSubmit = async (
    event: FormEvent<HTMLFormElement>,
  ): Promise<void> => {
    event.preventDefault();
    const controller = operation.current;
    if (
      busy.current ||
      !controller ||
      controller.signal.aborted ||
      !termsChecked ||
      !ageChecked ||
      request.status !== 'success' ||
      request.data === null
    )
      return;
    busy.current = true;
    setMutationType('accept');
    setMutation({ status: 'loading' });
    try {
      await acceptTerms(
        {
          termsVersion: request.data.terms.version,
          termsContentHash: request.data.terms.contentHash,
          privacyNoticeVersion: request.data.privacy.version,
          privacyNoticeContentHash: request.data.privacy.contentHash,
          accepted: true,
          ageConfirmed: true,
        },
        { signal: controller.signal },
      );
      if (!controller.signal.aborted)
        void navigate(returnTo, { replace: true });
    } catch (error: unknown) {
      if (controller.signal.aborted) return;
      const changed =
        error instanceof RequestError &&
        error.problem.code === 'LEGAL_DOCUMENT_CHANGED';
      setMutation({
        status: 'error',
        error: new Error(
          changed ? CONSENT_COPY.changed : CONSENT_COPY.submitError,
        ),
      });
      if (changed) {
        setTermsChecked(false);
        setAgeChecked(false);
        setAttempt((current): number => current + 1);
      }
    } finally {
      if (operation.current === controller) busy.current = false;
    }
  };

  const handleLogout = async (): Promise<void> => {
    const controller = operation.current;
    if (busy.current || !controller || controller.signal.aborted) return;
    busy.current = true;
    setMutationType('logout');
    setMutation({ status: 'loading' });
    try {
      const csrfToken = await requestCsrfToken();
      if (controller.signal.aborted) return;
      await logout({ csrfToken });
      // 서버 로그아웃이 끝났다면 화면을 벗어났어도 회원 상태를 정리한다.
      setAccessToken(null);
      try {
        localStorage.removeItem('harudle.has-completed-oauth');
        localStorage.removeItem(DIARY_GENERATION_TOAST_ID_KEY);
        removeGenerationId();
      } catch {
        /* 쿠키의 로그아웃 결과를 우선한다. */
      }
      try {
        resetUser();
      } catch {
        // 선택 분석 SDK의 오류가 이미 성공한 로그아웃을 되돌리지 않게 한다.
      }
      if (!controller.signal.aborted) window.location.replace('/landing');
    } catch {
      if (!controller.signal.aborted)
        setMutation({
          status: 'error',
          error: new Error(CONSENT_COPY.logoutError),
        });
    } finally {
      if (operation.current === controller) busy.current = false;
    }
  };

  const preventNavigationWhileMutating = (
    event: MouseEvent<HTMLAnchorElement>,
  ): void => {
    if (busy.current) event.preventDefault();
  };
  const suspended =
    request.status === 'error' &&
    request.error instanceof RequestError &&
    request.error.problem.code === 'ACCOUNT_SUSPENDED';
  const isLoading = request.status === 'idle' || request.status === 'loading';
  const form = request.status === 'success' ? request.data : null;

  return (
    <div css={pageStyle}>
      <header css={brandHeaderStyle}>
        <img src={harudleLogo} alt="하루들" css={logoStyle} />
      </header>
      <main css={contentStyle}>
        <div css={introStyle}>
          <h1>{CONSENT_COPY.heading}</h1>
          <p>{CONSENT_COPY.description}</p>
        </div>
        {isLoading ? (
          <div
            css={loadingStyle}
            role="status"
            aria-label={CONSENT_COPY.loading}
          >
            <LoadingSpinner />
          </div>
        ) : request.status === 'error' ? (
          <div role="alert" css={feedbackStyle}>
            <p>
              {suspended
                ? LOGIN_COPY.suspendedAccount
                : request.error.message === CONSENT_COPY.changed
                  ? CONSENT_COPY.changed
                  : CONSENT_COPY.loadError}
            </p>
            {!suspended && (
              <ActionButton
                label={CONSENT_COPY.retry}
                variant="secondary"
                disabled={isMutating}
                onClick={(): void => {
                  if (!busy.current)
                    setAttempt((current): number => current + 1);
                }}
              />
            )}
          </div>
        ) : form === null ? (
          <div css={feedbackStyle}>
            <p>{CONSENT_COPY.loginRequired}</p>
            <Link to="/login" state={{ returnTo }}>
              {CONSENT_COPY.login}
            </Link>
          </div>
        ) : (
          <form
            onSubmit={(event): void => {
              void handleSubmit(event);
            }}
            css={formStyle}
          >
            <div css={agreementGroupStyle}>
              <label css={[checkboxLabelStyle, selectAllStyle]}>
                <input
                  type="checkbox"
                  checked={allChecked}
                  disabled={isMutating}
                  onChange={(event): void => {
                    setTermsChecked(event.target.checked);
                    setAgeChecked(event.target.checked);
                  }}
                />
                {CONSENT_COPY.selectAll}
              </label>
              <div css={agreementCardStyle}>
                <div css={agreementRowStyle}>
                  <label css={checkboxLabelStyle}>
                    <input
                      type="checkbox"
                      checked={termsChecked}
                      disabled={isMutating}
                      onChange={(event): void =>
                        setTermsChecked(event.target.checked)
                      }
                    />
                    {CONSENT_COPY.terms}
                  </label>
                  <Link
                    to={`/legal/terms/${form.terms.version}`}
                    aria-label={CONSENT_COPY.termsView}
                    onClick={preventNavigationWhileMutating}
                    aria-disabled={isMutating}
                    tabIndex={isMutating ? -1 : undefined}
                  >
                    {CONSENT_COPY.view}
                    <span css={chevronStyle} aria-hidden="true" />
                  </Link>
                </div>
                <div css={agreementRowStyle}>
                  <label css={checkboxLabelStyle}>
                    <input
                      type="checkbox"
                      checked={ageChecked}
                      disabled={isMutating}
                      onChange={(event): void =>
                        setAgeChecked(event.target.checked)
                      }
                    />
                    {CONSENT_COPY.minimumAge}
                  </label>
                </div>
              </div>
            </div>
            <div css={privacyStyle}>
              <span>{CONSENT_COPY.privacy}</span>
              <Link
                to={`/legal/privacy/${form.privacy.version}`}
                aria-label={CONSENT_COPY.privacyView}
                onClick={preventNavigationWhileMutating}
                aria-disabled={isMutating}
                tabIndex={isMutating ? -1 : undefined}
              >
                {CONSENT_COPY.view}
                <span css={chevronStyle} aria-hidden="true" />
              </Link>
            </div>
            <div css={actionStyle}>
              <ActionButton
                type="submit"
                label={
                  isMutating && mutationType === 'accept'
                    ? CONSENT_COPY.submitting
                    : CONSENT_COPY.submit
                }
                disabled={!termsChecked || !ageChecked || isMutating}
              />
            </div>
          </form>
        )}
        {mutation.status === 'error' && (
          <p role="alert" css={mutationErrorStyle}>
            {mutation.error.message}
          </p>
        )}
        {!isLoading &&
          !(request.status === 'success' && request.data === null) && (
            <div css={declineStyle}>
              <ActionButton
                variant="secondary"
                icon={<span css={logoutIconStyle} />}
                disabled={isMutating}
                onClick={(): void => {
                  void handleLogout();
                }}
                label={
                  isMutating && mutationType === 'logout'
                    ? CONSENT_COPY.loggingOut
                    : CONSENT_COPY.decline
                }
              />
            </div>
          )}
      </main>
    </div>
  );
};

export default ConsentPage;

const pageStyle = css`
  display: flex;
  flex-direction: column;
  height: 100%;
  padding: 0 24px;
  background-color: ${theme.colors.background.surface};
  color: ${theme.colors.foreground.neutral};
`;
const brandHeaderStyle = css`
  display: flex;
  flex-shrink: 0;
  align-items: center;
  justify-content: center;
  height: 64px;
  border-bottom: 1px solid ${theme.colors.stroke.divider};
`;
const logoStyle = css`
  width: 106px;
  height: 71px;
  object-fit: contain;
`;
const contentStyle = css`
  display: flex;
  flex: 1;
  flex-direction: column;
  gap: 8px;
  min-height: 0;
  overflow-y: auto;
  padding: 20px 0 max(12px, env(safe-area-inset-bottom));
  a {
    color: ${theme.colors.foreground.brand};
    text-decoration: none;
  }
  a[aria-disabled='true'] {
    opacity: 0.5;
    cursor: default;
  }
`;
const introStyle = css`
  padding-bottom: 12px;
  h1 {
    font-size: 26px;
    font-weight: 700;
    line-height: 36px;
    letter-spacing: -0.8px;
    word-break: keep-all;
  }
  p {
    margin-top: 12px;
    color: ${theme.colors.foreground.neutralMuted};
    font-size: 14px;
    line-height: 24px;
    word-break: keep-all;
  }
`;
const formStyle = css`
  display: flex;
  flex: 1 0 auto;
  flex-direction: column;
  gap: 12px;
  a {
    display: inline-flex;
    align-items: center;
    justify-content: flex-end;
    gap: 4px;
    min-width: 44px;
    min-height: 44px;
    font-size: 13px;
  }
`;
const agreementGroupStyle = css`
  border-bottom: 1px solid ${theme.colors.stroke.divider};
`;
const agreementCardStyle = css`
  padding: 8px 0;
`;
const checkboxLabelStyle = css`
  display: flex;
  align-items: center;
  gap: 12px;
  min-height: 44px;
  font-size: 14px;
  line-height: 22px;
  cursor: pointer;
  input {
    appearance: none;
    flex-shrink: 0;
    width: 24px;
    height: 24px;
    border: 1.5px solid ${theme.colors.foreground.placeholder};
    border-radius: 50%;
    background-color: ${theme.colors.background.surface};
    cursor: inherit;
    &:checked {
      border-color: ${theme.colors.stroke.brandStrong};
      background: ${theme.colors.background.brandStrong} url(${checkIcon})
        center / 20px no-repeat;
    }
    &:focus-visible {
      outline: 3px solid ${theme.colors.stroke.focusRing};
      outline-offset: 3px;
    }
    &:disabled {
      opacity: 0.5;
      cursor: default;
    }
  }
`;
const selectAllStyle = css`
  min-height: 56px;
  padding-bottom: 8px;
  border-bottom: 1px solid ${theme.colors.stroke.divider};
  font-size: 16px;
  font-weight: 700;
`;
const agreementRowStyle = css`
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  min-height: 56px;
  a {
    flex-shrink: 0;
  }
`;
const chevronStyle = css`
  width: 14px;
  height: 14px;
  background-color: currentColor;
  mask: url(${chevronIcon}) center / contain no-repeat;
`;
const privacyStyle = css`
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  min-height: 44px;
  color: ${theme.colors.foreground.neutralMuted};
  font-size: 13px;
  line-height: 22px;
`;
const actionStyle = css`
  margin-top: auto;
  padding-top: 16px;
  button {
    border-radius: 14px;
    font-weight: 600;
  }
`;
const declineStyle = css`
  flex-shrink: 0;
  button {
    height: 44px;
    padding: 8px;
    border: none;
    background: none;
    color: ${theme.colors.foreground.neutralMuted};
    font-size: 13px;
  }
`;
const logoutIconStyle = css`
  display: block;
  width: 18px;
  height: 18px;
  margin: auto;
  background-color: currentColor;
  mask: url(${logoutIcon}) center / contain no-repeat;
`;
const mutationErrorStyle = css`
  color: ${theme.colors.foreground.critical};
  font-size: 13px;
  line-height: 22px;
  text-align: center;
`;
const loadingStyle = css`
  flex: 1;
`;
const feedbackStyle = css`
  display: grid;
  gap: 16px;
  padding: 24px 0;
  color: ${theme.colors.foreground.neutralMuted};
  font-size: 14px;
  line-height: 22px;
  text-align: center;
`;
