import { FEED_DETAIL_COPY } from './copy';
import { css } from '@emotion/react';
import { theme } from '../../styles/theme';

const FeedCommentEmpty = () => {
  return (
    <div css={emptyStateStyle}>
      <div css={emptyStateContentStyle}>
        <div css={emptyStateDescriptionContentStyle}>
          <div css={emptyStateTitleStyle}>
            {FEED_DETAIL_COPY.commentsEmptyTitle}
          </div>
          <div css={emptyStateDescriptionStyle}>
            {FEED_DETAIL_COPY.emptyComments}
          </div>
        </div>
      </div>
    </div>
  );
};

export default FeedCommentEmpty;

const emptyStateStyle = css`
  display: flex;
  flex: 1;
  flex-direction: column;
  justify-content: center;
  align-items: center;
  width: 100%;
  height: 100%;
`;

const emptyStateContentStyle = css`
  display: flex;
  flex-shrink: 0;
  flex-direction: column;
  align-items: center;
  width: 100%;
`;

const emptyStateDescriptionContentStyle = css`
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 8px;
  width: 100%;
  margin-bottom: 30px;
  text-align: center;
`;

const emptyStateTitleStyle = css`
  word-break: keep-all;
  width: 100%;
  color: ${theme.colors.foreground.neutral};
  font-size: 22px;
  font-weight: 700;
  line-height: 32px;
`;

const emptyStateDescriptionStyle = css`
  width: 100%;
  color: ${theme.colors.foreground.neutralMuted};
  font-size: 15px;
  font-weight: 400;
  word-break: keep-all;
`;
