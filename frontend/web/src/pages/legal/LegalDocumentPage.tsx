import { css } from '@emotion/react';
import { useEffect, useState, type ReactElement } from 'react';
import { useNavigate, useParams } from 'react-router';
import {
  getLegalDocument,
  getLegalDocuments,
  type LegalDocument,
  type LegalDocumentType,
} from '../../domain/legal/legalDocuments';
import { RequestError, type ApiRequest } from '../../shared/api';
import ActionButton from '../../shared/ActionButton';
import LoadingSpinner from '../../shared/LoadingSpinner';
import { isRecord } from '../../shared/utils';
import { theme } from '../../styles/theme';
import backIcon from '../../assets/icons/back.svg';
import harudleLogo from '../../assets/images/harudle-logo.webp';
import { LEGAL_COPY } from './copy';

interface LegalDocumentPageProps {
  readonly type: LegalDocumentType;
}

const LegalDocumentPage = ({ type }: LegalDocumentPageProps) => {
  const { version } = useParams<{ version: string }>();
  const navigate = useNavigate();
  const handleBack = (): void => {
    const historyState: unknown = window.history.state;
    if (
      isRecord(historyState) &&
      typeof historyState.idx === 'number' &&
      historyState.idx > 0
    ) {
      void navigate(-1);
      return;
    }
    void navigate('/', { replace: true });
  };

  return (
    <div css={pageStyle}>
      <header css={brandHeaderStyle}>
        <button
          type="button"
          aria-label={LEGAL_COPY.back}
          css={backButtonStyle}
          onClick={handleBack}
        >
          <img src={backIcon} alt="" />
        </button>
        <img src={harudleLogo} alt="하루들" css={logoStyle} />
      </header>
      <LegalDocumentContent
        key={`${type}/${version ?? 'current'}`}
        type={type}
        version={version}
      />
    </div>
  );
};

interface LegalDocumentContentProps extends LegalDocumentPageProps {
  readonly version: string | undefined;
}

const LegalDocumentContent = ({ type, version }: LegalDocumentContentProps) => {
  const navigate = useNavigate();
  const [request, setRequest] = useState<ApiRequest<LegalDocument>>({
    status: 'loading',
  });
  const [attempt, setAttempt] = useState(0);

  useEffect((): (() => void) => {
    const controller = new AbortController();
    const execute = async (): Promise<void> => {
      setRequest({ status: 'loading' });
      try {
        const document =
          version === undefined
            ? (
                await getLegalDocuments({ signal: controller.signal })
              ).documents.find((item): boolean => item.type === type)
            : await getLegalDocument(type, version, {
                signal: controller.signal,
              });
        if (!document) throw new Error(LEGAL_COPY.loadError);
        if (!controller.signal.aborted)
          setRequest({ status: 'success', data: document });
      } catch (error: unknown) {
        if (!controller.signal.aborted)
          setRequest({
            status: 'error',
            error:
              error instanceof Error ? error : new Error(LEGAL_COPY.loadError),
          });
      }
    };
    void execute();
    return (): void => controller.abort();
  }, [type, version, attempt]);

  if (request.status === 'idle' || request.status === 'loading')
    return (
      <main css={feedbackStyle} role="status" aria-label={LEGAL_COPY.loading}>
        <LoadingSpinner />
      </main>
    );
  if (request.status === 'error') {
    const notFound =
      request.error instanceof RequestError &&
      request.error.problem.code === 'LEGAL_DOCUMENT_NOT_FOUND';
    return (
      <main css={feedbackStyle}>
        <div role="alert" css={errorStyle}>
          <p>{notFound ? LEGAL_COPY.notFound : LEGAL_COPY.loadError}</p>
          <ActionButton
            label={notFound ? LEGAL_COPY.current : LEGAL_COPY.retry}
            variant="secondary"
            onClick={(): void => {
              if (notFound) {
                void navigate(
                  type === 'TERMS' ? '/legal/terms' : '/legal/privacy',
                  { replace: true },
                );
                return;
              }
              setAttempt((current): number => current + 1);
            }}
          />
        </div>
      </main>
    );
  }

  const document = request.data;
  return (
    <main css={documentStyle}>
      <article>
        <h1 css={documentTitleStyle}>{document.title}</h1>
        <dl css={metadataStyle}>
          <div>
            <dt>{LEGAL_COPY.effectiveDate}</dt>
            <dd>{document.effectiveDate.replaceAll('-', '.')}</dd>
          </div>
          <div>
            <dt>{LEGAL_COPY.version}</dt>
            <dd>{document.version}</dd>
          </div>
        </dl>
        {document.sections.map((section, index): ReactElement => (
          <section key={`${index}-${section.title}`} css={sectionStyle}>
            <h2>{section.title}</h2>
            {section.paragraphs.map(
              (paragraph, paragraphIndex): ReactElement => (
                <p key={paragraphIndex}>{paragraph}</p>
              ),
            )}
          </section>
        ))}
        <a
          href={document.sourceUrl}
          target="_blank"
          rel="noopener noreferrer"
          css={sourceLinkStyle}
        >
          {LEGAL_COPY.source}
          <span aria-hidden="true">↗</span>
        </a>
      </article>
    </main>
  );
};

export default LegalDocumentPage;

const pageStyle = css`
  display: flex;
  flex-direction: column;
  width: 100%;
  height: 100%;
  padding: 0 24px;
  background-color: ${theme.colors.background.surface};
  color: ${theme.colors.foreground.neutral};
`;
const brandHeaderStyle = css`
  display: grid;
  grid-template-columns: 40px minmax(0, 1fr) 40px;
  flex-shrink: 0;
  align-items: center;
  height: 64px;
  border-bottom: 1px solid ${theme.colors.stroke.divider};
`;
const logoStyle = css`
  justify-self: center;
  width: 106px;
  height: 71px;
  object-fit: contain;
`;
const backButtonStyle = css`
  display: flex;
  align-items: center;
  justify-content: center;
  width: 40px;
  height: 40px;
  padding: 8px;
  border: none;
  border-radius: 50%;
  background: none;
  cursor: pointer;
  img {
    width: 24px;
    height: 24px;
  }
`;
const documentStyle = css`
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  padding: 32px 0;
  overflow-wrap: anywhere;
`;
const documentTitleStyle = css`
  font-size: 26px;
  line-height: 38px;
  font-weight: 700;
  letter-spacing: -0.8px;
  word-break: keep-all;
`;
const metadataStyle = css`
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 16px;
  padding: 20px 0 28px;
  div {
    display: grid;
    gap: 6px;
  }
  dt {
    color: ${theme.colors.foreground.neutralMuted};
    font-size: 12px;
    line-height: 18px;
  }
  dd {
    font-size: 14px;
    line-height: 22px;
  }
`;
const sectionStyle = css`
  padding: 24px 0;
  border-top: 1px solid ${theme.colors.stroke.divider};
  h2 {
    margin-bottom: 14px;
    font-size: 16px;
    line-height: 26px;
    font-weight: 700;
    word-break: keep-all;
  }
  p {
    white-space: pre-line;
    font-size: 14px;
    line-height: 26px;
    color: ${theme.colors.foreground.neutralMuted};
    & + p {
      margin-top: 12px;
    }
  }
`;
const feedbackStyle = css`
  flex: 1;
  display: flex;
  align-items: center;
  justify-content: center;
  min-height: 0;
`;
const errorStyle = css`
  display: grid;
  gap: 16px;
  width: min(100%, 280px);
  text-align: center;
  color: ${theme.colors.foreground.neutralMuted};
  font-size: 14px;
  line-height: 22px;
`;
const sourceLinkStyle = css`
  display: flex;
  align-items: center;
  justify-content: space-between;
  min-height: 56px;
  border-top: 1px solid ${theme.colors.stroke.divider};
  color: ${theme.colors.foreground.brand};
  font-size: 13px;
  line-height: 22px;
  text-decoration: none;
`;
