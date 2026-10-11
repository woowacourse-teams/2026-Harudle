import { css } from '@emotion/react';
import { useEffect, useRef, useState } from 'react';
import sendIcon from '../../assets/icons/feed-send.svg';
import linkIcon from '../../assets/icons/link.svg';
import checkIcon from '../../assets/icons/check.svg';
import type { Feed } from '../../domain/feeds/feedList';
import { useErrorTracking } from '../../posthog/useErrorTracking';
import BottomSheet, { type BottomSheetHandle } from '../../shared/BottomSheet';
import { ERROR_MESSAGES } from '../../shared/errorMessage';
import { theme } from '../../styles/theme';
import { FEED_COPY } from './copy';

const SHOW_COPIED_DURATION_MS = 2_000;

const FeedShareButton = ({ shareUrl }: Pick<Feed, 'shareUrl'>) => {
  const sheetRef = useRef<BottomSheetHandle>(null);
  const [isCopied, setIsCopied] = useState(false);
  const { captureError } = useErrorTracking();

  useEffect(() => {
    if (!isCopied) return;

    const timer = window.setTimeout(() => {
      setIsCopied(false);
    }, SHOW_COPIED_DURATION_MS);

    return () => window.clearTimeout(timer);
  }, [isCopied]);

  const handleCopyLink = async (): Promise<void> => {
    try {
      await navigator.clipboard.writeText(shareUrl);
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
        <img src={sendIcon} alt="공유 아이콘" width={24} height={24} />
      </button>
      <BottomSheet title={FEED_COPY.shareSheetTitle} ref={sheetRef}>
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
      <span>{isCopied ? FEED_COPY.copySuccess : FEED_COPY.copyShareLink}</span>
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
