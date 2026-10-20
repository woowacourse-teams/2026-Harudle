// 스크롤 전역 스코프로

import { useLayoutEffect, useRef } from 'react';
import type { FeedListRequest } from '../../domain/feeds/feedList';

interface UseFeedScrollRestoreProps extends Pick<
  FeedListRequest,
  'categoryId' | 'sort'
> {
  isListReady: boolean;
}
type FeedScrollKey =
  `category:${FeedListRequest['categoryId']}&sort:${FeedListRequest['sort']}`;

let feedScroll = 0;
let feedScrollKey: FeedScrollKey | undefined = undefined;

const useFeedScrollRestore = ({
  categoryId,
  sort,
  isListReady,
}: UseFeedScrollRestoreProps) => {
  const targetScrollRef = useRef<HTMLDivElement>(null);

  const initFeedScroll = (): void => {
    feedScroll = 0;
  };

  const setFeedScroll = (nextScrollPosition: number): void => {
    feedScroll = nextScrollPosition;
  };

  useLayoutEffect(() => {
    const nextKey: FeedScrollKey = `category:${categoryId}&sort:${sort}`;
    if (feedScrollKey === nextKey) return;

    initFeedScroll();
    feedScrollKey = nextKey;
  }, [categoryId, sort]);

  useLayoutEffect(() => {
    if (!isListReady) {
      initFeedScroll();
      return;
    }

    targetScrollRef.current?.scrollTo({
      top: feedScroll,
      behavior: 'instant',
    });
  }, [isListReady]);

  return { targetScrollRef, initFeedScroll, setFeedScroll };
};

export default useFeedScrollRestore;
