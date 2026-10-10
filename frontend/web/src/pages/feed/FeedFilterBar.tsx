import { css } from '@emotion/react';
import feedSortDownIcon from '../../assets/icons/feed-sort-down.svg';
import { theme } from '../../styles/theme';
import { FEED_COPY } from './copy';
import FeedCategoryList, {
  type FeedCategoryListProps,
} from './FeedCategoryList';

interface FeedFilterBarProps {
  readonly selectedCategoryId: FeedCategoryListProps['selectedCategoryId'];
  readonly onCategoryChange: FeedCategoryListProps['onCategoryChange'];
}

const FeedFilterBar = ({
  selectedCategoryId,
  onCategoryChange,
}: FeedFilterBarProps) => {
  return (
    <div css={feedFilterBarStyle}>
      <FeedCategoryList
        selectedCategoryId={selectedCategoryId}
        onCategoryChange={onCategoryChange}
      />
      <button type="button" css={feedSortSelectStyle}>
        <span>{FEED_COPY.latestSort}</span>
        <span css={sortIconSlotStyle}>
          <img src={feedSortDownIcon} alt="" />
        </span>
      </button>
    </div>
  );
};

export default FeedFilterBar;

const feedFilterBarStyle = css`
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  width: 100%;
  height: 48px;
  flex-shrink: 0;
  border-bottom: 1px solid ${theme.colors.stroke.divider};
  letter-spacing: -0.35px;
`;

const feedSortSelectStyle = css`
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: 3px;
  flex-shrink: 0;
  width: 72px;
  height: 40px;
  padding: 0;
  border: none;
  background: none;
  color: ${theme.colors.foreground.neutralMuted};
  font-family: inherit;
  font-size: 12px;
  font-weight: 400;
  line-height: 20px;
  letter-spacing: inherit;
  cursor: pointer;
`;

const sortIconSlotStyle = css`
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
  width: 18px;
  height: 18px;
  overflow: hidden;

  img {
    display: block;
    flex-shrink: 0;
  }
`;
