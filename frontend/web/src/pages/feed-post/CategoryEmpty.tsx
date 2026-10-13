import { css } from '@emotion/react';
import { theme } from '../../styles/theme';
import { FEED_POST_COPY } from './copy';

const CategoryEmpty = () => (
  <p css={emptyStyle}>{FEED_POST_COPY.noCategories}</p>
);

export default CategoryEmpty;

const emptyStyle = css`
  padding: 24px 20px;
  color: ${theme.colors.foreground.neutralMuted};
  line-height: 22px;
  text-align: center;
  word-break: keep-all;
`;
