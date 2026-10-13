import { FEED_DETAIL_COPY } from './copy';
import { css } from '@emotion/react';
import { theme } from '../../styles/theme';

interface FeedCommentErrorProps {
  readonly onRetry: () => void;
}

const FeedCommentError = ({ onRetry }: FeedCommentErrorProps) => {
  return (
    <div css={feedErrorStyle} role="alert">
      <p css={titleStyle}>{FEED_DETAIL_COPY.commentsLoadErrorTitle}</p>

      <button type="button" css={retryButtonStyle} onClick={onRetry}>
        {FEED_DETAIL_COPY.reloadAction}
      </button>
    </div>
  );
};

export default FeedCommentError;

const feedErrorStyle = css`
  display: flex;
  flex-direction: column;
  justify-content: center;
  align-items: center;
  gap: 12px;
  width: 100%;
  padding: 24px 20px;
`;

const titleStyle = css`
  word-break: keep-all;
  color: ${theme.colors.foreground.neutralMuted};
  font-size: 15px;
  font-weight: 500;
  line-height: 22px;
  text-align: center;
`;

const retryButtonStyle = css`
  height: 40px;
  padding: 8px 16px;
  border: none;
  border-radius: 12px;
  background-color: ${theme.colors.background.brandSolid};
  color: ${theme.colors.foreground.onBrand};
  font-size: 14px;
  font-weight: 500;
  line-height: 20px;
  cursor: pointer;

  &:active {
    transform: scale(0.98);
  }
`;
