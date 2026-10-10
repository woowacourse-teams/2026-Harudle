import { css } from '@emotion/react';
import { theme } from '../../styles/theme';
import FeedItemActions from './FeedItemActions';

const FeedItem = () => {
  return (
    <article css={feedItemStyle}>
      <span>FeedItem</span>
      <FeedItemActions />
    </article>
  );
};

export default FeedItem;

const feedItemStyle = css`
  display: flex;
  flex-direction: column;
  gap: 8px;
  padding: 8px;
  border: 2px dashed ${theme.colors.stroke.brandStrong};
`;
