import { css } from '@emotion/react';
import feedSortDownIcon from '../../assets/icons/feed-sort-down.svg';
import { isFeedSort, type FeedSort } from '../../domain/feeds/feedList';
import { theme } from '../../styles/theme';
import { FEED_COPY } from './copy';
import FeedCategoryList, {
  type FeedCategoryListProps,
} from './FeedCategoryList';

interface FeedFilterBarProps {
  readonly selectedCategoryId: FeedCategoryListProps['selectedCategoryId'];
  readonly onCategoryChange: FeedCategoryListProps['onCategoryChange'];
  readonly sort: FeedSort;
  readonly onSortChange: (sort: FeedSort) => void;
}

const FeedFilterBar = ({
  selectedCategoryId,
  onCategoryChange,
  sort,
  onSortChange,
}: FeedFilterBarProps) => {
  return (
    <div css={feedFilterBarStyle}>
      <FeedCategoryList
        selectedCategoryId={selectedCategoryId}
        onCategoryChange={onCategoryChange}
      />
      <select
        css={feedSortSelectStyle}
        value={sort}
        onChange={(event): void => {
          const nextSort = event.currentTarget.value;

          // 검증된 select 옵션만 전달되지만, DOM의 value 타입은 string이므로 타입을 좁혀준다.
          if (isFeedSort(nextSort)) onSortChange(nextSort);
        }}
      >
        <option value="LATEST">{FEED_COPY.latestSort}</option>
        <option value="POPULAR">{FEED_COPY.popularSort}</option>
      </select>
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
`;

const feedSortSelectStyle = css`
  appearance: none;
  flex-shrink: 0;
  width: 72px;
  height: 40px;
  padding: 0 21px 0 0;
  border: none;
  background-color: transparent;
  background-image: url(${feedSortDownIcon});
  background-repeat: no-repeat;
  background-position: right -3px center;
  background-size: 24px 24px;
  color: ${theme.colors.foreground.neutralMuted};
  font-family: inherit;
  font-size: 12px;
  font-weight: 400;
  line-height: 20px;
  text-align: right;
  text-align-last: right;
  cursor: pointer;

  &:focus-visible {
    outline: 2px solid ${theme.colors.stroke.focusRing};
    outline-offset: -2px;
  }
`;
