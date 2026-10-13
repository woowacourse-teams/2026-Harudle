import { css } from '@emotion/react';
import { ERROR_MESSAGES } from '../../shared/errorMessage';
import { theme } from '../../styles/theme';
import { FEED_POST_COPY } from './copy';

interface CategoryErrorProps {
  readonly onRetry: () => void;
}

const CategoryError = ({ onRetry }: CategoryErrorProps) => (
  <div css={errorStyle} role="alert">
    <p css={messageStyle}>{ERROR_MESSAGES.CATEGORY_LIST_FETCH_FAILED}</p>
    <button type="button" css={retryButtonStyle} onClick={onRetry}>
      {FEED_POST_COPY.retryAction}
    </button>
  </div>
);

export default CategoryError;

const errorStyle = css`
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 12px;
  padding: 24px 20px;
`;

const messageStyle = css`
  color: ${theme.colors.foreground.neutralMuted};
  line-height: 22px;
  text-align: center;
  word-break: keep-all;
`;

const retryButtonStyle = css`
  height: 40px;
  padding: 8px 16px;
  border: none;
  border-radius: 12px;
  background-color: ${theme.colors.background.brandSolid};
  color: ${theme.colors.foreground.onBrand};
  font-size: 14px;
  cursor: pointer;
`;
