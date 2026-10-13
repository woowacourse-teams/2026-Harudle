import {
  afterEach,
  beforeEach,
  describe,
  expect,
  it,
  jest,
} from '@jest/globals';
import userEvent from '@testing-library/user-event';
import { act, fireEvent, render, screen } from '@testing-library/react';
import type {
  Feed,
  FeedListResponse,
  getFeedList,
} from '../../domain/feeds/feedList';
import { ERROR_MESSAGES } from '../../shared/errorMessage';
import FeedItemList from './FeedItemList';
import type { setFeedLike } from '../../domain/feeds/feedLike';
import type { deleteFeed } from '../../domain/feeds/feedDelete';

const mockTrack =
  jest.fn<(event: string, properties: Record<string, unknown>) => void>();
jest.mock('../../posthog/useAnalytics', () => ({
  useAnalytics: () => ({ track: mockTrack }),
}));

jest.mock('../../assets/images/loading-animation.webp', () => 'loading.webp');
jest.mock('../../assets/images/empty-person-and-dog.png', () => 'empty.png');
jest.mock('../../assets/images/request-fail.png', () => 'error.png');
jest.mock('../../assets/icons/kakao.svg', () => 'kakao.svg');
jest.mock('../../assets/icons/feed-heart.svg', () => 'feed-heart.svg');
jest.mock(
  '../../assets/icons/feed-heart-filled.svg',
  () => 'feed-heart-filled.svg',
);
jest.mock(
  '../../assets/icons/bottom-sheet-close.svg',
  () => 'bottom-sheet-close.svg',
);
jest.mock('../../posthog/useErrorTracking', () => ({
  useErrorTracking: () => ({ captureError: jest.fn() }),
}));
jest.mock('../../assets/icons/feed-comment.svg', () => 'feed-comment.svg');
jest.mock('../../assets/icons/feed-send.svg', () => 'feed-send.svg');
jest.mock('../../assets/icons/feed-more.svg', () => 'feed-more.svg');
jest.mock('../../assets/icons/delete.svg', () => 'delete.svg');
jest.mock('../../assets/icons/link.svg', () => 'link.svg');
jest.mock('../../assets/icons/check.svg', () => 'check.svg');
jest.mock(
  '../../assets/icons/feed-category-chevron.svg',
  () => 'feed-category-chevron.svg',
);
jest.mock(
  '../../assets/images/profile-fallback.svg',
  () => 'profile-fallback.svg',
);
const mockNavigate = jest.fn();
jest.mock('react-router', () => ({ useNavigate: () => mockNavigate }));

const mockGetFeedList = jest.fn<typeof getFeedList>();
const mockOnCategoryClick =
  jest.fn<(categoryId: Feed['category']['id'], categoryName: string) => void>();
const mockSetFeedLike = jest.fn<typeof setFeedLike>();
const mockDeleteFeed = jest.fn<typeof deleteFeed>();
jest.mock('../../domain/feeds/feedLike', () => ({
  setFeedLike: (
    ...args: Parameters<typeof setFeedLike>
  ): ReturnType<typeof setFeedLike> => mockSetFeedLike(...args),
}));
jest.mock('../../domain/feeds/feedDelete', () => ({
  deleteFeed: (
    ...args: Parameters<typeof deleteFeed>
  ): ReturnType<typeof deleteFeed> => mockDeleteFeed(...args),
}));
jest.mock('../../domain/feeds/feedList', () => ({
  getFeedList: (
    ...args: Parameters<typeof getFeedList>
  ): ReturnType<typeof getFeedList> => mockGetFeedList(...args),
}));

const feed: Feed = {
  id: '00000000-0000-4000-8000-000000000001',
  author: {
    id: '08d69a34-6d70-4d42-a158-671bc67733c9',
    nickname: '하루들',
    profileImageUrl: '/profile.svg',
  },
  category: { id: 1, name: '일상' },
  imageUrl: '/diary.webp',
  imageUrlExpiresAt: '2026-10-10T16:00:00+09:00',
  publishedAt: '2026-10-10T15:00:00+09:00',
  likeCount: 12,
  commentCount: 3,
  likedByMe: false,
  isMine: false,
  shareUrl: 'https://harudle.com/feeds/00000000-0000-4000-8000-000000000001',
};

const response: FeedListResponse = {
  items: [feed],
  nextCursor: null,
  hasNext: false,
};
const emptyResponse: FeedListResponse = {
  items: [],
  nextCursor: null,
  hasNext: false,
};

beforeEach((): void => {
  localStorage.setItem('harudle.has-completed-oauth', 'true');
  mockGetFeedList.mockReset();
  mockGetFeedList.mockResolvedValue(response);
  mockSetFeedLike.mockReset();
  mockDeleteFeed.mockReset();
});

afterEach((): void => {
  localStorage.removeItem('harudle.has-completed-oauth');
  localStorage.removeItem('harudle.has-ever-logged-in');
  jest.restoreAllMocks();
  jest.useRealTimers();
});

const prepareDialogs = (): void => {
  // JSDOM에 없는 dialog API를 렌더링된 요소에만 보완한다.
  document.querySelectorAll('dialog').forEach((dialog): void => {
    Object.defineProperties(dialog, {
      showModal: { value: (): void => dialog.setAttribute('open', '') },
      close: { value: (): void => dialog.removeAttribute('open') },
    });
  });
};

describe('피드 카드 액션', (): void => {
  it('카드 카테고리를 누르면 해당 카테고리 ID를 전달한다', async (): Promise<void> => {
    const user = userEvent.setup();
    render(
      <FeedItemList sort="LATEST" onCategoryClick={mockOnCategoryClick} />,
    );

    await user.click(await screen.findByRole('button', { name: '일상' }));
    expect(mockOnCategoryClick).toHaveBeenCalledWith(
      feed.category.id,
      feed.category.name,
    );
    expect(mockOnCategoryClick).toHaveBeenCalledTimes(1);
    expect(mockNavigate).not.toHaveBeenCalled();
  });

  it('댓글을 누르면 해당 피드 상세 페이지로 이동한다', async (): Promise<void> => {
    const user = userEvent.setup();
    render(
      <FeedItemList onCategoryClick={mockOnCategoryClick} sort="LATEST" />,
    );

    await user.click(await screen.findByRole('button', { name: '3' }));

    expect(mockNavigate).toHaveBeenCalledWith(`/feeds/${feed.id}`);
  });

  it('비로그인 좋아요는 요청 없이 로그인 안내를 열고 닫거나 로그인 페이지로 이동한다', async (): Promise<void> => {
    localStorage.removeItem('harudle.has-completed-oauth');
    // 과거 로그인 경험만 있어도 현재 로그아웃 상태면 안내한다.
    localStorage.setItem('harudle.has-ever-logged-in', 'true');
    const alert = jest.spyOn(window, 'alert').mockImplementation(() => {});
    const user = userEvent.setup();
    render(
      <FeedItemList onCategoryClick={mockOnCategoryClick} sort="LATEST" />,
    );
    const button = await screen.findByRole('button', { name: '12' });
    prepareDialogs();

    await user.click(button);
    const sheet = screen.getByRole('dialog');
    expect(sheet).toHaveTextContent('로그인하고 계속할까요?');
    expect(sheet).toHaveTextContent('좋아요를 누르려면 로그인해 주세요.');
    expect(mockSetFeedLike).not.toHaveBeenCalled();
    expect(mockNavigate).not.toHaveBeenCalled();
    expect(alert).not.toHaveBeenCalled();

    await user.click(screen.getByRole('button', { name: '계속 둘러보기' }));
    expect(sheet).not.toHaveAttribute('open');
    expect(screen.getByRole('article')).toBeInTheDocument();
    expect(button).toHaveTextContent('12');

    await user.click(button);
    expect(sheet).toHaveAttribute('open');
    await user.click(screen.getByRole('button', { name: '카카오로 로그인' }));
    expect(sheet).not.toHaveAttribute('open');
    expect(mockNavigate).toHaveBeenCalledWith('/login');
    expect(mockSetFeedLike).not.toHaveBeenCalled();
  });

  it('좋아요와 취소의 서버 응답을 반영하고 목록은 다시 조회하지 않는다', async (): Promise<void> => {
    const user = userEvent.setup();
    mockSetFeedLike.mockResolvedValueOnce({ likedByMe: true, likeCount: 13 });
    mockSetFeedLike.mockResolvedValueOnce({ likedByMe: false, likeCount: 12 });
    render(
      <FeedItemList onCategoryClick={mockOnCategoryClick} sort="LATEST" />,
    );

    await user.click(await screen.findByRole('button', { name: '12' }));
    const liked = await screen.findByRole('button', {
      name: '13',
    });
    expect(liked.querySelector('img')).toHaveAttribute(
      'src',
      'feed-heart-filled.svg',
    );
    expect(mockSetFeedLike).toHaveBeenNthCalledWith(1, {
      feedId: feed.id,
      likedByMe: true,
    });

    await user.click(liked);
    const unliked = await screen.findByRole('button', { name: '12' });
    expect(unliked.querySelector('img')).toHaveAttribute(
      'src',
      'feed-heart.svg',
    );
    expect(mockSetFeedLike).toHaveBeenNthCalledWith(2, {
      feedId: feed.id,
      likedByMe: false,
    });
    expect(mockTrack).toHaveBeenCalledWith(
      'api_requested',
      expect.objectContaining({ operation: 'feed_like', action: 'like' }),
    );
    expect(mockTrack).toHaveBeenCalledWith(
      'api_requested',
      expect.objectContaining({ operation: 'feed_like', action: 'unlike' }),
    );
    expect(mockGetFeedList).toHaveBeenCalledTimes(1);
  });

  it('좋아요 처리 중 중복 요청을 막고 실패하면 기존 수를 유지한 채 재시도한다', async (): Promise<void> => {
    const alert = jest
      .spyOn(window, 'alert')
      .mockImplementation((): void => {});
    const user = userEvent.setup();
    let rejectLike: ((reason: Error) => void) | undefined;
    mockSetFeedLike.mockImplementationOnce(
      (): ReturnType<typeof setFeedLike> =>
        new Promise((_resolve, reject): void => {
          rejectLike = reject;
        }),
    );
    mockSetFeedLike.mockResolvedValueOnce({ likedByMe: true, likeCount: 13 });
    render(
      <FeedItemList onCategoryClick={mockOnCategoryClick} sort="LATEST" />,
    );
    const button = await screen.findByRole('button', { name: '12' });
    await user.click(button);
    expect(button).toBeDisabled();
    await user.click(button);
    expect(mockSetFeedLike).toHaveBeenCalledTimes(1);

    await act(async (): Promise<void> => {
      rejectLike?.(new Error('좋아요 변경 실패'));
    });
    expect(button).toBeEnabled();
    expect(button).toHaveTextContent('12');
    expect(alert).toHaveBeenCalledWith('좋아요 변경 실패');
    await user.click(button);
    expect(
      await screen.findByRole('button', { name: '13' }),
    ).toBeInTheDocument();
    alert.mockRestore();
  });

  it('링크 복사 성공 후 2초 동안 완료 아이콘과 복사됨을 표시한다', async () => {
    const user = userEvent.setup();
    const writeText = jest
      .spyOn(navigator.clipboard, 'writeText')
      .mockResolvedValue(undefined);
    const alert = jest.spyOn(window, 'alert').mockImplementation(() => {});
    render(
      <FeedItemList onCategoryClick={mockOnCategoryClick} sort="LATEST" />,
    );
    await screen.findByRole('article');
    prepareDialogs();
    await user.click(screen.getByRole('button', { name: '공유 아이콘' }));
    const sheet = screen.getByRole('dialog');
    expect(sheet).toHaveAttribute('open');
    const copyButton = screen.getByRole('button', { name: '링크 복사' });
    expect(copyButton.querySelector('span')).toHaveStyle({
      mask: 'url(link.svg) center/contain no-repeat',
    });
    jest.useFakeTimers();
    await act(async () => {
      fireEvent.click(copyButton);
    });
    expect(writeText).toHaveBeenCalledWith(feed.shareUrl);
    expect(alert).not.toHaveBeenCalled();
    expect(sheet).toHaveAttribute('open');
    const copiedButton = screen.getByRole('button', { name: '복사됨' });
    expect(copiedButton.querySelector('span')).toHaveStyle({
      mask: 'url(check.svg) center/contain no-repeat',
    });
    await act(async () => {
      fireEvent.click(copiedButton);
    });
    expect(writeText).toHaveBeenCalledTimes(2);
    act(() => {
      jest.advanceTimersByTime(1_999);
    });
    expect(copiedButton).toHaveTextContent('복사됨');
    act(() => {
      jest.advanceTimersByTime(1);
    });
    expect(
      screen.getByRole('button', { name: '링크 복사' }).querySelector('span'),
    ).toHaveStyle({ mask: 'url(link.svg) center/contain no-repeat' });
    expect(mockSetFeedLike).not.toHaveBeenCalled();
    writeText.mockRestore();
    alert.mockRestore();
  });

  it('링크 복사에 실패하면 바텀시트를 유지하고 재시도할 수 있다', async () => {
    const user = userEvent.setup();
    const writeText = jest
      .spyOn(navigator.clipboard, 'writeText')
      .mockRejectedValueOnce(
        new DOMException('권한이 없습니다.', 'NotAllowedError'),
      )
      .mockResolvedValueOnce(undefined);
    const alert = jest.spyOn(window, 'alert').mockImplementation(() => {});
    render(
      <FeedItemList onCategoryClick={mockOnCategoryClick} sort="LATEST" />,
    );
    await screen.findByRole('article');
    prepareDialogs();
    await user.click(screen.getByRole('button', { name: '공유 아이콘' }));
    const sheet = screen.getByRole('dialog');
    await user.click(screen.getByRole('button', { name: '링크 복사' }));
    expect(sheet).toHaveAttribute('open');
    expect(alert).toHaveBeenCalledWith(ERROR_MESSAGES.FEED_LINK_COPY_FAILED);

    await user.click(screen.getByRole('button', { name: '링크 복사' }));
    expect(writeText).toHaveBeenCalledTimes(2);
    expect(sheet).toHaveAttribute('open');
    expect(screen.getByRole('button', { name: '복사됨' })).toBeInTheDocument();
    writeText.mockRestore();
    alert.mockRestore();
  });

  it('비로그인도 선택한 피드의 링크를 복사한다', async () => {
    localStorage.removeItem('harudle.has-completed-oauth');
    const user = userEvent.setup();
    const writeText = jest
      .spyOn(navigator.clipboard, 'writeText')
      .mockResolvedValue(undefined);
    const secondFeed = {
      ...feed,
      id: 'second',
      shareUrl: 'https://harudle.com/feeds/second',
    };
    mockGetFeedList.mockResolvedValue({
      ...response,
      items: [feed, secondFeed],
    });
    render(
      <FeedItemList onCategoryClick={mockOnCategoryClick} sort="LATEST" />,
    );
    const shareButtons = await screen.findAllByRole('button', {
      name: '공유 아이콘',
    });
    prepareDialogs();
    await user.click(shareButtons[1]);
    await user.click(screen.getByRole('button', { name: '링크 복사' }));
    expect(writeText).toHaveBeenCalledWith(secondFeed.shareUrl);
    expect(mockSetFeedLike).not.toHaveBeenCalled();
  });

  it('복사 완료 후 화면을 떠나면 초기화 타이머를 정리한다', async () => {
    const user = userEvent.setup();
    jest.spyOn(navigator.clipboard, 'writeText').mockResolvedValue(undefined);
    const { unmount } = render(
      <FeedItemList onCategoryClick={mockOnCategoryClick} sort="LATEST" />,
    );
    await screen.findByRole('article');
    prepareDialogs();
    await user.click(screen.getByRole('button', { name: '공유 아이콘' }));
    jest.useFakeTimers();
    const setTimeout = jest.spyOn(window, 'setTimeout');
    const clearTimeout = jest.spyOn(window, 'clearTimeout');
    await act(async () => {
      fireEvent.click(screen.getByRole('button', { name: '링크 복사' }));
    });
    const timerIndex = setTimeout.mock.calls.findIndex(
      ([, delay]) => delay === 2_000,
    );
    expect(timerIndex).toBeGreaterThanOrEqual(0);
    const timer = setTimeout.mock.results[timerIndex].value;
    if (typeof timer !== 'number') throw new Error('초기화 타이머가 없습니다.');
    unmount();
    expect(clearTimeout).toHaveBeenCalledWith(timer);
  });

  it('클립보드가 없으면 실패 안내를 표시하고 바텀시트를 유지한다', async () => {
    const user = userEvent.setup();
    const descriptor = Object.getOwnPropertyDescriptor(navigator, 'clipboard');
    Object.defineProperty(navigator, 'clipboard', {
      configurable: true,
      value: undefined,
    });
    const alert = jest.spyOn(window, 'alert').mockImplementation(() => {});
    try {
      render(
        <FeedItemList onCategoryClick={mockOnCategoryClick} sort="LATEST" />,
      );
      await screen.findByRole('article');
      prepareDialogs();
      await user.click(screen.getByRole('button', { name: '공유 아이콘' }));
      await user.click(screen.getByRole('button', { name: '링크 복사' }));
      expect(alert).toHaveBeenCalledWith(ERROR_MESSAGES.FEED_LINK_COPY_FAILED);
      expect(screen.getByRole('dialog')).toHaveAttribute('open');
      expect(screen.getByRole('button', { name: '링크 복사' })).toBeEnabled();
    } finally {
      if (descriptor) Object.defineProperty(navigator, 'clipboard', descriptor);
    }
  });

  it('삭제 실패와 중복 클릭은 카드를 유지하고 재시도 성공 후 제거한다', async (): Promise<void> => {
    const user = userEvent.setup();
    const alert = jest
      .spyOn(window, 'alert')
      .mockImplementation((): void => {});
    let rejectDelete: ((reason: Error) => void) | undefined;
    mockGetFeedList.mockResolvedValue({
      ...response,
      items: [{ ...feed, isMine: true }],
    });
    mockDeleteFeed.mockImplementationOnce(
      (): Promise<void> =>
        new Promise((_resolve, reject): void => {
          rejectDelete = reject;
        }),
    );
    mockDeleteFeed.mockResolvedValueOnce(undefined);
    render(
      <FeedItemList onCategoryClick={mockOnCategoryClick} sort="LATEST" />,
    );
    await screen.findByRole('article');
    prepareDialogs();
    await user.click(
      screen.getByRole('button', { name: '옵션 더보기 아이콘' }),
    );
    await user.click(screen.getByRole('button', { name: /피드 삭제$/ }));
    await user.click(screen.getByRole('button', { name: /피드 삭제$/ }));
    await user.click(screen.getByRole('button', { name: '삭제 중...' }));
    expect(mockDeleteFeed).toHaveBeenCalledTimes(1);
    expect(screen.getByRole('article')).toBeInTheDocument();

    await act(async (): Promise<void> => {
      rejectDelete?.(new Error('피드 삭제 실패'));
    });
    expect(screen.getByRole('dialog')).toHaveAttribute('open');
    expect(screen.getByRole('article')).toBeInTheDocument();
    expect(alert).toHaveBeenCalledWith('피드 삭제 실패');
    await user.click(screen.getByRole('button', { name: /피드 삭제$/ }));
    expect(
      await screen.findByText('아직 이 카테고리에 공유된 피드가 없어요.'),
    ).toBeInTheDocument();
    expect(mockDeleteFeed).toHaveBeenCalledTimes(2);
    alert.mockRestore();
  });

  it('삭제 취소는 피드를 유지하고 확인 성공 후에만 목록에서 제거한다', async (): Promise<void> => {
    const user = userEvent.setup();
    mockGetFeedList.mockResolvedValue({
      ...response,
      items: [{ ...feed, isMine: true }],
    });
    mockDeleteFeed.mockResolvedValue(undefined);
    render(
      <FeedItemList onCategoryClick={mockOnCategoryClick} sort="LATEST" />,
    );
    await screen.findByRole('article');
    prepareDialogs();

    await user.click(
      screen.getByRole('button', { name: '옵션 더보기 아이콘' }),
    );
    await user.click(screen.getByRole('button', { name: /피드 삭제$/ }));
    expect(screen.getByRole('dialog')).toHaveTextContent(
      '앨범의 원본 네컷만화는 그대로 남아요.',
    );
    await user.click(screen.getByRole('button', { name: '취소' }));
    expect(mockDeleteFeed).not.toHaveBeenCalled();
    expect(screen.getByRole('article')).toBeInTheDocument();

    await user.click(
      screen.getByRole('button', { name: '옵션 더보기 아이콘' }),
    );
    await user.click(screen.getByRole('button', { name: /피드 삭제$/ }));
    await user.click(screen.getByRole('button', { name: /피드 삭제$/ }));
    expect(
      await screen.findByText('아직 이 카테고리에 공유된 피드가 없어요.'),
    ).toBeInTheDocument();
    expect(mockDeleteFeed).toHaveBeenCalledWith({ feedId: feed.id });
    expect(screen.queryByRole('article')).not.toBeInTheDocument();
  });
});

describe('피드 목록 더 보기', (): void => {
  const firstPage: FeedListResponse = {
    ...response,
    nextCursor: 'cursor-1',
    hasNext: true,
  };
  const secondFeed: Feed = {
    ...feed,
    id: 'second',
    author: { ...feed.author, nickname: '두 번째 작성자' },
  };

  it('서버 커서로 이어서 조회하고 마지막 페이지에서 버튼을 숨긴다', async (): Promise<void> => {
    const user = userEvent.setup();
    mockGetFeedList
      .mockResolvedValueOnce(firstPage)
      .mockResolvedValueOnce({
        items: [secondFeed],
        nextCursor: 'cursor-2',
        hasNext: true,
      })
      .mockResolvedValueOnce({
        ...response,
        items: [{ ...secondFeed, id: 'third' }],
      });
    render(
      <FeedItemList
        onCategoryClick={mockOnCategoryClick}
        categoryId={1}
        sort="POPULAR"
      />,
    );
    const button = await screen.findByRole('button', { name: '더 보기' });
    expect(mockGetFeedList).toHaveBeenNthCalledWith(1, {
      categoryId: 1,
      sort: 'POPULAR',
      size: 20,
      signal: expect.any(AbortSignal),
    });

    await user.click(button);
    expect(
      await screen.findByText(secondFeed.author.nickname),
    ).toBeInTheDocument();
    expect(screen.getAllByRole('article')).toHaveLength(2);
    expect(mockGetFeedList).toHaveBeenNthCalledWith(2, {
      categoryId: 1,
      sort: 'POPULAR',
      size: 20,
      cursor: 'cursor-1',
      signal: expect.any(AbortSignal),
    });

    await user.click(screen.getByRole('button', { name: '더 보기' }));
    expect(screen.getAllByRole('article')).toHaveLength(3);
    expect(mockGetFeedList).toHaveBeenNthCalledWith(3, {
      categoryId: 1,
      sort: 'POPULAR',
      size: 20,
      cursor: 'cursor-2',
      signal: expect.any(AbortSignal),
    });
    expect(
      screen.queryByRole('button', { name: '더 보기' }),
    ).not.toBeInTheDocument();
  });

  it('추가 조회 중 기존 목록과 좋아요 변경을 유지하고 중복 요청을 막는다', async (): Promise<void> => {
    const user = userEvent.setup();
    let resolveNext: ((value: FeedListResponse) => void) | undefined;
    mockGetFeedList.mockResolvedValueOnce(firstPage).mockImplementationOnce(
      (): Promise<FeedListResponse> =>
        new Promise((resolve): void => {
          resolveNext = resolve;
        }),
    );
    mockSetFeedLike.mockResolvedValueOnce({ likedByMe: true, likeCount: 13 });
    render(
      <FeedItemList onCategoryClick={mockOnCategoryClick} sort="LATEST" />,
    );
    await user.click(await screen.findByRole('button', { name: '더 보기' }));
    const pendingButton = screen.getByRole('button', {
      name: '불러오는 중...',
    });
    expect(pendingButton).toBeDisabled();
    fireEvent.click(pendingButton);
    expect(mockGetFeedList).toHaveBeenCalledTimes(2);
    expect(screen.getByRole('article')).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: '12' }));
    expect(
      await screen.findByRole('button', { name: '13' }),
    ).toBeInTheDocument();

    await act(async (): Promise<void> => {
      resolveNext?.({ ...response, items: [secondFeed] });
    });
    expect(screen.getAllByRole('article')).toHaveLength(2);
    expect(screen.getByRole('button', { name: '13' })).toBeInTheDocument();
  });

  it('추가 조회 실패를 alert로 안내하고 같은 커서로 다시 더 보기 할 수 있다', async (): Promise<void> => {
    const user = userEvent.setup();
    const alert = jest
      .spyOn(window, 'alert')
      .mockImplementation((): void => {});
    mockGetFeedList
      .mockResolvedValueOnce(firstPage)
      .mockRejectedValueOnce(new Error('추가 조회 실패'))
      .mockResolvedValueOnce({ ...response, items: [secondFeed] });
    render(
      <FeedItemList onCategoryClick={mockOnCategoryClick} sort="LATEST" />,
    );
    await user.click(await screen.findByRole('button', { name: '더 보기' }));
    expect(alert).toHaveBeenCalledWith(
      '피드를 더 불러오지 못했어요. 다시 시도해 주세요.',
    );
    expect(screen.getByRole('article')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '더 보기' })).toBeEnabled();
    await user.click(screen.getByRole('button', { name: '더 보기' }));
    expect(
      await screen.findByText(secondFeed.author.nickname),
    ).toBeInTheDocument();
    expect(screen.getAllByRole('article')).toHaveLength(2);
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
    expect(mockGetFeedList.mock.calls[1][0]?.cursor).toBe('cursor-1');
    expect(mockGetFeedList.mock.calls[2][0]?.cursor).toBe('cursor-1');
  });

  it.each([
    { categoryId: 2, sort: 'POPULAR' as const },
    { categoryId: 1, sort: 'LATEST' as const },
  ])(
    '필터 변경 시 추가 조회를 취소하고 커서를 초기화한다: %j',
    async (filter): Promise<void> => {
      const user = userEvent.setup();
      let resolvePrevious: ((value: FeedListResponse) => void) | undefined;
      mockGetFeedList
        .mockResolvedValueOnce(firstPage)
        .mockImplementationOnce(
          (): Promise<FeedListResponse> =>
            new Promise((resolve): void => {
              resolvePrevious = resolve;
            }),
        )
        .mockResolvedValueOnce({
          ...firstPage,
          items: [secondFeed],
          nextCursor: 'new-cursor',
        })
        .mockResolvedValueOnce(emptyResponse);
      const { rerender } = render(
        <FeedItemList
          onCategoryClick={mockOnCategoryClick}
          categoryId={1}
          sort="POPULAR"
        />,
      );
      await user.click(await screen.findByRole('button', { name: '더 보기' }));
      const previousSignal = mockGetFeedList.mock.calls[1][0]?.signal;
      rerender(
        <FeedItemList onCategoryClick={mockOnCategoryClick} {...filter} />,
      );
      expect(previousSignal?.aborted).toBe(true);
      expect(
        await screen.findByText(secondFeed.author.nickname),
      ).toBeInTheDocument();
      expect(screen.queryByText(feed.author.nickname)).not.toBeInTheDocument();
      expect(mockGetFeedList).toHaveBeenNthCalledWith(3, {
        ...filter,
        size: 20,
        signal: expect.any(AbortSignal),
      });

      await act(async (): Promise<void> => {
        resolvePrevious?.(response);
      });
      expect(screen.getAllByRole('article')).toHaveLength(1);
      await user.click(screen.getByRole('button', { name: '더 보기' }));
      expect(mockGetFeedList).toHaveBeenLastCalledWith({
        ...filter,
        size: 20,
        cursor: 'new-cursor',
        signal: expect.any(AbortSignal),
      });
    },
  );

  it('추가 조회 중 피드를 삭제해도 삭제 결과를 유지하고 새 피드를 추가한다', async (): Promise<void> => {
    const user = userEvent.setup();
    let resolveNext: ((value: FeedListResponse) => void) | undefined;
    mockGetFeedList
      .mockResolvedValueOnce({
        ...firstPage,
        items: [{ ...feed, isMine: true }],
      })
      .mockImplementationOnce(
        (): Promise<FeedListResponse> =>
          new Promise((resolve): void => {
            resolveNext = resolve;
          }),
      );
    mockDeleteFeed.mockResolvedValueOnce(undefined);
    render(
      <FeedItemList onCategoryClick={mockOnCategoryClick} sort="POPULAR" />,
    );
    await user.click(await screen.findByRole('button', { name: '더 보기' }));
    prepareDialogs();
    await user.click(
      screen.getByRole('button', { name: '옵션 더보기 아이콘' }),
    );
    await user.click(screen.getByRole('button', { name: /피드 삭제$/ }));
    await user.click(screen.getByRole('button', { name: /피드 삭제$/ }));
    expect(screen.queryByRole('article')).not.toBeInTheDocument();
    expect(
      screen.getByRole('button', { name: '불러오는 중...' }),
    ).toBeDisabled();

    await act(async (): Promise<void> => {
      resolveNext?.({ ...response, items: [secondFeed] });
    });
    expect(screen.getAllByRole('article')).toHaveLength(1);
    expect(screen.queryByText(feed.author.nickname)).not.toBeInTheDocument();
    expect(screen.getByText(secondFeed.author.nickname)).toBeInTheDocument();
  });
});

describe('피드 목록 조회 상태', () => {
  it('로딩 후 조회한 항목 수만큼 기존 카드를 표시한다', async (): Promise<void> => {
    mockGetFeedList.mockResolvedValue({
      ...response,
      items: [feed, { ...feed, id: 'second' }],
    });
    render(
      <FeedItemList
        onCategoryClick={mockOnCategoryClick}
        categoryId={1}
        sort="POPULAR"
      />,
    );

    expect(screen.getByRole('img', { name: '로딩 중' })).toBeInTheDocument();
    expect(await screen.findAllByRole('article')).toHaveLength(2);
    expect(mockGetFeedList).toHaveBeenCalledWith({
      categoryId: 1,
      sort: 'POPULAR',
      size: 20,
      signal: expect.any(AbortSignal),
    });
  });

  it('조회한 반응 수와 카드 액션 UI를 표시한다', async (): Promise<void> => {
    render(
      <FeedItemList onCategoryClick={mockOnCategoryClick} sort="LATEST" />,
    );
    await screen.findByRole('article');

    expect(screen.queryByText('FeedItemActions')).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: '12' })).toHaveTextContent('12');
    expect(screen.getByRole('button', { name: '3' })).toHaveTextContent('3');
    expect(
      screen.getByRole('button', { name: '공유 아이콘' }),
    ).toBeInTheDocument();
    expect(
      screen.queryByRole('button', { name: '옵션 더보기 아이콘' }),
    ).not.toBeInTheDocument();
  });

  it('본인 피드에만 더보기 버튼을 표시한다', async (): Promise<void> => {
    mockGetFeedList.mockResolvedValue({
      ...response,
      items: [{ ...feed, isMine: true }],
    });
    render(
      <FeedItemList onCategoryClick={mockOnCategoryClick} sort="LATEST" />,
    );

    expect(
      await screen.findByRole('button', { name: '옵션 더보기 아이콘' }),
    ).toBeInTheDocument();
  });

  it('프로필 이미지 로딩에 실패하면 기본 이미지로 대체한다', async () => {
    render(
      <FeedItemList onCategoryClick={mockOnCategoryClick} sort="LATEST" />,
    );
    const article = await screen.findByRole('article');
    const image = article.querySelector('header img');
    if (!image) throw new Error('프로필 이미지가 없습니다.');

    expect(image).toHaveAttribute('src', '/profile.svg');
    fireEvent.error(image);
    expect(image).toHaveAttribute('src', 'profile-fallback.svg');
  });

  it('빈 응답은 FeedEmpty를 표시한다', async (): Promise<void> => {
    mockGetFeedList.mockResolvedValue(emptyResponse);
    render(
      <FeedItemList onCategoryClick={mockOnCategoryClick} sort="LATEST" />,
    );

    expect(
      await screen.findByText('아직 이 카테고리에 공유된 피드가 없어요.'),
    ).toBeInTheDocument();
    expect(
      screen.getByRole('button', { name: '네컷만화 고르기' }),
    ).toBeInTheDocument();
  });

  it('카테고리별 빈 화면에서 만화를 고르면 앨범으로 이동한다', async (): Promise<void> => {
    const user = userEvent.setup();
    mockGetFeedList.mockResolvedValue(emptyResponse);
    render(
      <FeedItemList
        onCategoryClick={mockOnCategoryClick}
        categoryId={2}
        sort="LATEST"
      />,
    );
    expect(
      await screen.findByText('아직 이 카테고리에 공유된 피드가 없어요.'),
    ).toBeInTheDocument();
    expect(screen.getByText('첫 번째 피드를 올려보세요!')).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: '네컷만화 고르기' }));
    expect(mockNavigate).toHaveBeenCalledWith('/album');
  });

  it.each([new Error('조회에 실패했습니다.'), '알 수 없는 오류'])(
    '조회 실패 시 FeedError에 오류 메시지를 표시한다: %s',
    async (error): Promise<void> => {
      mockGetFeedList.mockRejectedValue(error);
      render(
        <FeedItemList onCategoryClick={mockOnCategoryClick} sort="LATEST" />,
      );

      const alert = await screen.findByRole('alert');
      expect(alert).toHaveTextContent(
        error instanceof Error
          ? error.message
          : ERROR_MESSAGES.FEED_LIST_FETCH_FAILED,
      );
      expect(
        screen.getByRole('button', { name: '다시 불러오기' }),
      ).toBeInTheDocument();
    },
  );

  it('필터 변경 시 이전 요청을 취소하고 늦은 응답을 무시한다', async (): Promise<void> => {
    let resolvePrevious: ((value: FeedListResponse) => void) | undefined;
    mockGetFeedList.mockImplementationOnce(
      (): Promise<FeedListResponse> =>
        new Promise((resolve): void => {
          resolvePrevious = resolve;
        }),
    );
    mockGetFeedList.mockResolvedValueOnce(emptyResponse);
    const { rerender } = render(
      <FeedItemList
        onCategoryClick={mockOnCategoryClick}
        categoryId={1}
        sort="LATEST"
      />,
    );
    const previousSignal = mockGetFeedList.mock.calls[0][0]?.signal;

    rerender(
      <FeedItemList
        onCategoryClick={mockOnCategoryClick}
        categoryId={2}
        sort="POPULAR"
      />,
    );
    expect(previousSignal?.aborted).toBe(true);
    expect(
      await screen.findByText('아직 이 카테고리에 공유된 피드가 없어요.'),
    ).toBeInTheDocument();
    expect(mockGetFeedList).toHaveBeenLastCalledWith({
      categoryId: 2,
      sort: 'POPULAR',
      size: 20,
      signal: expect.any(AbortSignal),
    });

    await act(async (): Promise<void> => {
      resolvePrevious?.(response);
    });
    expect(screen.queryByRole('article')).not.toBeInTheDocument();
    expect(
      screen.getByText('아직 이 카테고리에 공유된 피드가 없어요.'),
    ).toBeInTheDocument();
  });
});
