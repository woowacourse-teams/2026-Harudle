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
import PageHeader from '../../shared/PageHeader';
import { isRecord } from '../../shared/utils';
import { theme } from '../../styles/theme';
import backIcon from '../../assets/icons/back.svg';
import { LEGAL_COPY } from './copy';

interface LegalDocumentPageProps {
  readonly type: LegalDocumentType;
}

const LegalDocumentPage = ({ type }: LegalDocumentPageProps) => {
  const { version } = useParams<{ version: string }>();
  const navigate = useNavigate();
  const title = type === 'TERMS' ? LEGAL_COPY.terms : LEGAL_COPY.privacy;
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
      <PageHeader
        title={title}
        left={
          <button
            type="button"
            aria-label={LEGAL_COPY.back}
            css={backButtonStyle}
            onClick={handleBack}
          >
            <img src={backIcon} alt="" />
          </button>
        }
        right={null}
      />
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
        <h2 css={documentTitleStyle}>{document.title}</h2>
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
            <h3>{section.title}</h3>
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
  padding: 0 20px;
  background: ${theme.colors.background.surface};
  color: ${theme.colors.foreground.neutral};
  > header {
    flex-shrink: 0;
  }
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
  padding: 16px 0 32px;
  overflow-wrap: anywhere;
`;

const documentTitleStyle = css`
  font-size: 20px;
  line-height: 30px;
  font-weight: 700;
`;

const metadataStyle = css`
  display: flex;
  flex-wrap: wrap;
  gap: 8px 16px;
  padding: 10px 0 20px;
  margin-bottom: 24px;
  border-bottom: 1px solid ${theme.colors.stroke.divider};
  color: ${theme.colors.foreground.neutralMuted};
  font-size: 12px;
  line-height: 20px;
  div {
    display: flex;
    gap: 6px;
  }
`;

const sectionStyle = css`
  margin-bottom: 24px;
  h3 {
    margin-bottom: 10px;
    font-size: 16px;
    line-height: 24px;
    font-weight: 700;
  }
  p {
    margin-bottom: 10px;
    white-space: pre-line;
    font-size: 14px;
    line-height: 24px;
    color: ${theme.colors.foreground.neutralMuted};
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
  color: ${theme.colors.foreground.brand};
  font-size: 13px;
  line-height: 22px;
  text-underline-offset: 3px;
`;
