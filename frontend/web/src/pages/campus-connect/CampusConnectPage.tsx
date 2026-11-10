import { css } from '@emotion/react';
import { useEffect, useState } from 'react';
import { useNavigate, useSearchParams } from 'react-router';
import ActionButton from '../../shared/ActionButton';
import { RequestError } from '../../shared/api';
import { theme } from '../../styles/theme';
import {
  campusCallbackUrl,
  issueCampusLinkToken,
  openCampus,
  openInNewTab,
} from './campusLinkApi';
import { CAMPUS_CONNECT_COPY } from './copy';

const STATE_PATTERN = /^[A-Za-z0-9_-]{43,128}$/;
const LOGIN_HISTORY_KEY = 'harudle.has-completed-oauth';

const hasLoginHistory = (): boolean => {
  try {
    return localStorage.getItem(LOGIN_HISTORY_KEY) !== null;
  } catch {
    return false;
  }
};

type Problem = 'terms' | 'profile' | 'unavailable' | 'failed';

const problemOf = (error: unknown): Problem => {
  if (!(error instanceof RequestError)) return 'failed';
  if (error.problem.code === 'TERMS_ACCEPTANCE_REQUIRED') return 'terms';
  if (error.problem.code === 'PUBLIC_PROFILE_NOT_ASSIGNED') return 'profile';
  if (error.problem.status === 404) return 'unavailable';
  return 'failed';
};

const PROBLEM_MESSAGE: Record<Problem, string> = {
  terms: CAMPUS_CONNECT_COPY.termsRequired,
  profile: CAMPUS_CONNECT_COPY.profileRequired,
  unavailable: CAMPUS_CONNECT_COPY.unavailable,
  failed: CAMPUS_CONNECT_COPY.failed,
};

// 로그인 여부: 다른 탭에서 로그인을 마치면(로그인 기록 저장) 이 탭도 바로 알아챈다.
const useLoginHistory = (): boolean => {
  const [isLoggedIn, setIsLoggedIn] = useState(hasLoginHistory);
  useEffect((): (() => void) => {
    const check = (): void => setIsLoggedIn(hasLoginHistory());
    window.addEventListener('storage', check);
    window.addEventListener('focus', check);
    return (): void => {
      window.removeEventListener('storage', check);
      window.removeEventListener('focus', check);
    };
  }, []);
  return isLoggedIn;
};

// 우테코 캠퍼스 → 이 화면 → [연동하기] → 캠퍼스로 돌아간다.
// 하루들 토큰은 넘기지 않고, 60초짜리 서명 토큰만 캠퍼스 주소에 붙인다.
const CampusConnectPage = () => {
  const navigate = useNavigate();
  const [searchParams] = useSearchParams();
  const state = searchParams.get('state') ?? '';
  const isLoggedIn = useLoginHistory();
  const [isSubmitting, setIsSubmitting] = useState(false);
  const [problem, setProblem] = useState<Problem | null>(null);
  const [popupBlocked, setPopupBlocked] = useState(false);

  const openTab = (path: string): void => {
    setPopupBlocked(!openInNewTab(path));
  };

  const connect = async (): Promise<void> => {
    setIsSubmitting(true);
    setProblem(null);
    try {
      const issued = await issueCampusLinkToken(state);
      openCampus(campusCallbackUrl(issued, state));
    } catch (error: unknown) {
      setProblem(problemOf(error));
      setIsSubmitting(false);
    }
  };

  if (!STATE_PATTERN.test(state))
    return (
      <main css={pageStyle}>
        <p role="alert" css={descriptionStyle}>
          {CAMPUS_CONNECT_COPY.invalidLink}
        </p>
      </main>
    );

  if (!isLoggedIn)
    return (
      <main css={pageStyle}>
        <div css={messageBoxStyle}>
          <h1 css={titleStyle}>{CAMPUS_CONNECT_COPY.loginTitle}</h1>
          <p css={descriptionStyle}>{CAMPUS_CONNECT_COPY.loginDescription}</p>
          {popupBlocked && (
            <p role="alert" css={errorStyle}>
              {CAMPUS_CONNECT_COPY.popupBlocked}
            </p>
          )}
        </div>
        <div css={buttonRowStyle}>
          <ActionButton
            label={CAMPUS_CONNECT_COPY.login}
            onClick={(): void => openTab('/login')}
          />
        </div>
      </main>
    );

  return (
    <main css={pageStyle}>
      <div css={messageBoxStyle}>
        <h1 css={titleStyle}>{CAMPUS_CONNECT_COPY.title}</h1>
        <p css={descriptionStyle}>{CAMPUS_CONNECT_COPY.description}</p>
        {problem !== null && (
          <p role="alert" css={errorStyle}>
            {PROBLEM_MESSAGE[problem]}
          </p>
        )}
      </div>
      <div css={buttonRowStyle}>
        <ActionButton
          label={CAMPUS_CONNECT_COPY.cancel}
          variant="secondary"
          onClick={(): void => void navigate('/', { replace: true })}
        />
        {problem === 'terms' ? (
          <ActionButton
            label={CAMPUS_CONNECT_COPY.openTerms}
            onClick={(): void => {
              setProblem(null);
              openTab('/consent');
            }}
          />
        ) : (
          <ActionButton
            label={
              isSubmitting
                ? CAMPUS_CONNECT_COPY.connecting
                : CAMPUS_CONNECT_COPY.confirm
            }
            disabled={isSubmitting}
            onClick={(): void => void connect()}
          />
        )}
      </div>
    </main>
  );
};

export default CampusConnectPage;

const pageStyle = css`
  display: flex;
  flex-direction: column;
  justify-content: center;
  align-items: center;
  gap: 24px;
  width: 100%;
  height: 100%;
  padding: 24px 20px;
`;

const messageBoxStyle = css`
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 8px;
  text-align: center;
`;

const titleStyle = css`
  color: ${theme.colors.foreground.neutral};
  font-size: 20px;
  font-weight: 700;
  line-height: 30px;
`;

const descriptionStyle = css`
  color: ${theme.colors.foreground.neutralMuted};
  font-size: 14px;
  line-height: 22px;
  word-break: keep-all;
`;

const errorStyle = css`
  color: ${theme.colors.foreground.critical};
  font-size: 14px;
  line-height: 22px;
  word-break: keep-all;
`;

const buttonRowStyle = css`
  display: flex;
  gap: 8px;
  width: 100%;
  max-width: 320px;
`;
