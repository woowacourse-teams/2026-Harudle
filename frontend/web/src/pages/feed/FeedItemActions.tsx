import { css } from '@emotion/react';
import { theme } from '../../styles/theme';

const FeedItemActions = () => {
  return <div css={feedItemActionsStyle}>FeedItemActions</div>;
};

export default FeedItemActions;

const feedItemActionsStyle = css`
  padding: 8px;
  border: 1px solid ${theme.colors.stroke.brandSolid};
`;
