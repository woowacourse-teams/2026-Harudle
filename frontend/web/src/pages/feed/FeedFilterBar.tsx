import { css } from '@emotion/react';
import feedRefreshIcon from '../../assets/icons/feed-refresh.svg';
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
  readonly onRefresh: () => Promise<void>;
  readonly isRefreshing: boolean;
}

const FeedFilterBar = ({
  selectedCategoryId,
  onCategoryChange,
  sort,
  onSortChange,
  onRefresh,
  isRefreshing,
}: FeedFilterBarProps) => {
  return (
    <div css={feedFilterBarStyle}>
      <FeedCategoryList
        selectedCategoryId={selectedCategoryId}
        onCategoryChange={onCategoryChange}
      />
      <div css={feedControlsStyle}>
        <button
          type="button"
          css={feedRefreshButtonStyle}
          disabled={isRefreshing}
          onClick={() => {
            void onRefresh();
          }}
        >
          <span css={feedRefreshIconStyle} />
        </button>
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
  width: 56px;
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

const feedControlsStyle = css`
  display: flex;
  align-items: center;
  flex-shrink: 0;
  gap: 2px;
`;

const feedRefreshButtonStyle = css`
  display: flex;
  align-items: center;
  justify-content: center;
  width: 40px;
  height: 40px;
  padding: 0;
  border: none;
  border-radius: 50%;
  background: none;
  color: ${theme.colors.foreground.neutralMuted};
  cursor: pointer;

  @media (hover: hover) and (pointer: fine) {
    &:hover:not(:disabled) {
      background-color: ${theme.colors.background.neutralSubtle};
    }
  }

  &:active:not(:disabled) {
    background-color: ${theme.colors.background.neutralSubtle};
  }

  &:disabled {
    cursor: default;
  }
`;

const feedRefreshIconStyle = css`
  width: 20px;
  height: 20px;
  background-color: currentColor;
  mask: url(${feedRefreshIcon}) center / contain no-repeat;
`;
