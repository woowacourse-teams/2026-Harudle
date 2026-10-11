import { css } from '@emotion/react';
import FeedCommentList from './FeedCommentList';
import { FEED_DETAIL_COPY } from './copy';
import { theme } from '../../styles/theme';

interface FeedCommentsProps {
  readonly feedId: string;
}

const FeedComments = ({ feedId }: FeedCommentsProps) => {
  return (
    <section css={sectionStyle}>
      <h2 css={headingStyle}>{FEED_DETAIL_COPY.commentsHeading}</h2>
      <FeedCommentList feedId={feedId} />
    </section>
  );
};

export default FeedComments;

const sectionStyle = css`
  padding: 20px 20px 12px;
  border-top: 1px solid ${theme.colors.stroke.divider};
`;

const headingStyle = css`
  margin-bottom: 8px;
  font-size: 16px;
  font-weight: 700;
`;
