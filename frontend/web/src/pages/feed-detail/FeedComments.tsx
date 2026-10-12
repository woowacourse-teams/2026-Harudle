import { css } from '@emotion/react';
import FeedCommentList from './FeedCommentList';
import type { ApiRequest } from '../../shared/api';
import type {
  FeedComment,
  FeedCommentsResponse,
} from '../../domain/feeds/feedComments';
import { FEED_DETAIL_COPY } from './copy';
import { theme } from '../../styles/theme';

interface FeedCommentsProps {
  readonly request: ApiRequest<FeedCommentsResponse>;
  readonly loadMoreRequest: ApiRequest<void>;
  readonly onLoadMore: () => void;
  readonly onRetry: () => void;
  readonly onDelete: (commentId: FeedComment['id']) => void;
}

const FeedComments = ({
  request,
  loadMoreRequest,
  onLoadMore,
  onRetry,
  onDelete,
}: FeedCommentsProps) => {
  return (
    <section css={sectionStyle}>
      <h2 css={headingStyle}>{FEED_DETAIL_COPY.commentsHeading}</h2>
      <FeedCommentList
        request={request}
        loadMoreRequest={loadMoreRequest}
        onLoadMore={onLoadMore}
        onRetry={onRetry}
        onDelete={onDelete}
      />
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
