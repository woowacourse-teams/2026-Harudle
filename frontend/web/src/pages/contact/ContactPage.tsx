import { css } from '@emotion/react';
import { useEffect, useRef, useState } from 'react';
import { Link } from 'react-router';
import backIcon from '../../assets/icons/back.svg';
import copyIcon from '../../assets/icons/content-copy.svg';
import ActionButton from '../../shared/ActionButton';
import PageHeader from '../../shared/PageHeader';
import { useToastContext } from '../../shared/ToastContext';
import type { ApiRequest } from '../../shared/api';
import { theme } from '../../styles/theme';
import { CONTACT_COPY, CONTACT_MAILTO_URL } from './copy';

const ContactPage = () => {
  const { addToast } = useToastContext();
  const [copyRequest, setCopyRequest] = useState<ApiRequest<void>>({
    status: 'idle',
  });
  const isCopying = useRef(false);
  const isMounted = useRef(true);

  useEffect((): (() => void) => {
    isMounted.current = true;
    return (): void => {
      isMounted.current = false;
    };
  }, []);

  const handleCopyEmail = async (): Promise<void> => {
    if (isCopying.current) return;

    isCopying.current = true;
    setCopyRequest({ status: 'loading' });

    try {
      if (typeof navigator.clipboard?.writeText !== 'function') {
        throw new Error(CONTACT_COPY.copyFailed);
      }

      await navigator.clipboard.writeText(CONTACT_COPY.emailAddress);
      if (!isMounted.current) return;

      setCopyRequest({ status: 'success', data: undefined });
      addToast({ type: 'success', message: CONTACT_COPY.copySuccess }, 2_000);
    } catch (error: unknown) {
      if (!isMounted.current) return;

      const copyError =
        error instanceof Error ? error : new Error(CONTACT_COPY.copyFailed);
      setCopyRequest({ status: 'error', error: copyError });
      addToast(
        {
          type: 'error',
          message: CONTACT_COPY.copyFailed,
          description: CONTACT_COPY.copyFallback,
        },
        3_000,
      );
    } finally {
      isCopying.current = false;
    }
  };

  const handleOpenMail = (): void => {
    window.location.href = CONTACT_MAILTO_URL;
  };

  return (
    <div css={pageStyle}>
      <PageHeader
        title={CONTACT_COPY.pageTitle}
        left={
          <Link to="/" aria-label={CONTACT_COPY.backLabel} css={backLinkStyle}>
            <img src={backIcon} alt="" width={24} height={24} />
          </Link>
        }
        right={null}
      />
      <main css={contentStyle}>
        <div css={introStyle}>
          <h2 css={titleStyle}>{CONTACT_COPY.title}</h2>
          <p css={descriptionStyle}>{CONTACT_COPY.description}</p>
        </div>

        <section css={emailCardStyle} aria-labelledby="contact-email-label">
          <h3 id="contact-email-label" css={emailLabelStyle}>
            {CONTACT_COPY.emailLabel}
          </h3>
          <p css={emailAddressStyle}>{CONTACT_COPY.emailAddress}</p>
          <p css={replyNoticeStyle}>{CONTACT_COPY.replyNotice}</p>
        </section>

        <div css={actionsStyle}>
          <ActionButton
            label={CONTACT_COPY.mailAction}
            onClick={handleOpenMail}
          />
          <ActionButton
            label={
              copyRequest.status === 'loading'
                ? CONTACT_COPY.copyPending
                : CONTACT_COPY.copyAction
            }
            icon={<img src={copyIcon} alt="" />}
            variant="secondary"
            disabled={copyRequest.status === 'loading'}
            onClick={() => void handleCopyEmail()}
          />
        </div>
      </main>
    </div>
  );
};

export default ContactPage;

const pageStyle = css`
  display: flex;
  flex-direction: column;
  height: 100%;
  padding: 0 20px;
  background-color: ${theme.colors.background.surface};
`;

const backLinkStyle = css`
  display: grid;
  place-items: center;
  width: 32px;
  height: 40px;
  color: ${theme.colors.foreground.neutral};

  &:focus-visible {
    outline: 2px solid ${theme.colors.stroke.focusRing};
    outline-offset: 2px;
    border-radius: 8px;
  }
`;

const contentStyle = css`
  display: flex;
  flex: 1;
  flex-direction: column;
  gap: 28px;
  min-height: 0;
  overflow-y: auto;
  padding: 28px 0 max(24px, env(safe-area-inset-bottom));
`;

const introStyle = css`
  display: flex;
  flex-direction: column;
  gap: 12px;
`;

const titleStyle = css`
  color: ${theme.colors.foreground.neutral};
  font-size: 24px;
  font-weight: 700;
  line-height: 34px;
  word-break: keep-all;
`;

const descriptionStyle = css`
  color: ${theme.colors.foreground.neutralMuted};
  font-size: 15px;
  line-height: 24px;
  word-break: keep-all;
`;

const emailCardStyle = css`
  padding: 20px;
  border: 1px solid ${theme.colors.stroke.outline};
  border-radius: 16px;
`;

const emailLabelStyle = css`
  color: ${theme.colors.foreground.neutralMuted};
  font-size: 13px;
  line-height: 20px;
`;

const emailAddressStyle = css`
  margin-top: 8px;
  color: ${theme.colors.foreground.neutral};
  font-size: 17px;
  font-weight: 600;
  line-height: 26px;
  overflow-wrap: anywhere;
  user-select: text;
`;

const replyNoticeStyle = css`
  margin-top: 16px;
  color: ${theme.colors.foreground.neutralMuted};
  font-size: 14px;
  line-height: 22px;
  word-break: keep-all;
  white-space: pre-line;
`;

const actionsStyle = css`
  display: flex;
  flex-direction: column;
  gap: 12px;
`;
