import { css } from '@emotion/react';
import { useEffect, useState } from 'react';
import { useNavigate, useSearchParams } from 'react-router';
import ActionButton from '../../shared/ActionButton';
import { RequestError } from '../../shared/api';
import { theme } from '../../styles/theme';
import {
  campusCallbackUrl,
  issueCampusLinkCode,
  openCampus,
} from './campusLinkApi';
import { CAMPUS_CONNECT_COPY } from './copy';
import {
  clearPostLoginPath,
  isAllowedPostLoginPath,
  savePostLoginPath,
} from './postLoginPath';

const hasLoginHistory = (): boolean => {
  try {
    return localStorage.getItem('harudle.has-completed-oauth') !== null;
  } catch {
    return false;
  }
};

const errorMessage = (error: unknown): string => {
  if (!(error instanceof RequestError)) return CAMPUS_CONNECT_COPY.failed;
  if (error.problem.code === 'PUBLIC_PROFILE_NOT_ASSIGNED')
    return CAMPUS_CONNECT_COPY.profileRequired;
  if (error.problem.code === 'CAMPUS_LINK_DISABLED')
    return CAMPUS_CONNECT_COPY.unavailable;
  return CAMPUS_CONNECT_COPY.failed;
};

// 우테코 캠퍼스 → 하루들 로그인 → [연동하기] → 캠퍼스로 돌아간다.
// 하루들 토큰은 넘기지 않고, 60초짜리 일회용 code만 캠퍼스 주소에 붙인다.
const CampusConnectPage = () => {
  const navigate = useNavigate();
  const [searchParams] = useSearchParams();
  const state = searchParams.get('state') ?? '';
  const path = `/campus/connect?state=${state}`;
  const isValid = isAllowedPostLoginPath(path);
  const isLoggedIn = hasLoginHistory();
  const [isSubmitting, setIsSubmitting] = useState(false);
  const [message, setMessage] = useState<string | null>(null);

  useEffect((): void => {
    if (!isValid) return;
    if (!isLoggedIn) {
      savePostLoginPath(path);
      void navigate('/login', { replace: true });
      return;
    }
    clearPostLoginPath();
  }, [isValid, isLoggedIn, path, navigate]);

  const connect = async (): Promise<void> => {
    setIsSubmitting(true);
    setMessage(null);
    try {
      const issued = await issueCampusLinkCode(state);
      openCampus(campusCallbackUrl(issued, state));
    } catch (error: unknown) {
      setMessage(errorMessage(error));
      setIsSubmitting(false);
    }
  };

  if (!isValid)
    return (
      <main css={pageStyle}>
        <p role="alert" css={descriptionStyle}>
          {CAMPUS_CONNECT_COPY.invalidLink}
        </p>
      </main>
    );
  if (!isLoggedIn) return null;

  return (
    <main css={pageStyle}>
      <div css={messageBoxStyle}>
        <h1 css={titleStyle}>{CAMPUS_CONNECT_COPY.title}</h1>
        <p css={descriptionStyle}>{CAMPUS_CONNECT_COPY.description}</p>
        {message !== null && (
          <p role="alert" css={errorStyle}>
            {message}
          </p>
        )}
      </div>
      <div css={buttonRowStyle}>
        <ActionButton
          label={CAMPUS_CONNECT_COPY.cancel}
          variant="secondary"
          onClick={(): void => void navigate('/', { replace: true })}
        />
        <ActionButton
          label={
            isSubmitting
              ? CAMPUS_CONNECT_COPY.connecting
              : CAMPUS_CONNECT_COPY.confirm
          }
          disabled={isSubmitting}
          onClick={(): void => void connect()}
        />
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
