import { css } from '@emotion/react';
import { useEffect, useRef, useState } from 'react';
import sendIcon from '../../assets/icons/feed-send.svg';
import linkIcon from '../../assets/icons/link.svg';
import checkIcon from '../../assets/icons/check.svg';
import type { Feed } from '../../domain/feeds/feedList';
import { useAnalytics } from '../../posthog/useAnalytics';
import { useErrorTracking } from '../../posthog/useErrorTracking';
import BottomSheet, { type BottomSheetHandle } from '../../shared/BottomSheet';
import { ERROR_MESSAGES } from '../../shared/errorMessage';
import { theme } from '../../styles/theme';
import { FEED_ACTION_COPY } from './copy';

const SHOW_COPIED_DURATION_MS = 2_000;

interface FeedShareButtonProps extends Pick<Feed, 'shareUrl'> {
  readonly feedId: Feed['id'];
}

const FeedShareButton = ({ feedId, shareUrl }: FeedShareButtonProps) => {
  const sheetRef = useRef<BottomSheetHandle>(null);
  const [isCopied, setIsCopied] = useState(false);
  const { track } = useAnalytics();
  const { captureError } = useErrorTracking();

  useEffect(() => {
    if (!isCopied) return;

    const timer = window.setTimeout(() => {
      setIsCopied(false);
    }, SHOW_COPIED_DURATION_MS);

    return () => window.clearTimeout(timer);
  }, [isCopied]);

  const handleCopyLink = async (): Promise<void> => {
    track('feed_link_copy_clicked', {
      feature: 'feed',
      operation: 'copy_link',
      feed_id: feedId,
    });

    try {
      await navigator.clipboard.writeText(`${shareUrl}?utm_source=feed_share`);
      setIsCopied(true);
    } catch (error: unknown) {
      const requestError =
        error instanceof Error
          ? error
          : new Error(ERROR_MESSAGES.FEED_LINK_COPY_FAILED);
      captureError(requestError, { feature: 'feed', operation: 'copy_link' });
      setIsCopied(false);
      window.alert(ERROR_MESSAGES.FEED_LINK_COPY_FAILED);
    }
  };

  return (
    <>
      <button
        type="button"
        css={shareButtonStyle}
        onClick={() => sheetRef.current?.open()}
      >
        <img src={sendIcon} alt="공유하기" width={24} height={24} />
      </button>
      <BottomSheet title={FEED_ACTION_COPY.shareSheetTitle} ref={sheetRef}>
        <CopyLinkButton isCopied={isCopied} onCopy={handleCopyLink} />
      </BottomSheet>
    </>
  );
};

export default FeedShareButton;

interface CopyLinkButtonProps {
  readonly isCopied: boolean;
  readonly onCopy: () => Promise<void>;
}

const CopyLinkButton = ({ isCopied, onCopy }: CopyLinkButtonProps) => {
  return (
    <button type="button" css={copyLinkButtonStyle} onClick={onCopy}>
      <span css={[iconStyle, isCopied ? checkIconStyle : linkIconStyle]} />
      <span>
        {isCopied
          ? FEED_ACTION_COPY.copySuccess
          : FEED_ACTION_COPY.copyShareLink}
      </span>
    </button>
  );
};

const shareButtonStyle = css`
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
  width: 32px;
  height: 32px;
  padding: 0;
  border: none;
  background: none;
  cursor: pointer;
`;

const copyLinkButtonStyle = css`
  display: flex;
  align-items: center;
  gap: 10px;
  width: 100%;
  min-height: 56px;
  padding: 12px 0;
  border: none;
  background: none;
  color: ${theme.colors.foreground.neutral};
  text-align: left;
  font-size: 16px;
  line-height: 24px;
  cursor: pointer;
`;

const iconStyle = css`
  display: block;
  flex-shrink: 0;
  width: 24px;
  height: 24px;
  background-color: currentColor;
`;

const linkIconStyle = css`
  mask: url(${linkIcon}) center / contain no-repeat;
`;

const checkIconStyle = css`
  mask: url(${checkIcon}) center / contain no-repeat;
`;
