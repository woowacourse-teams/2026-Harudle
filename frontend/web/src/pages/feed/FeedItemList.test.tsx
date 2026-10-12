import { beforeEach, describe, expect, it, jest } from '@jest/globals';
import userEvent from '@testing-library/user-event';
import { act, fireEvent, render, screen } from '@testing-library/react';
import type {
  Feed,
  FeedListResponse,
  getFeedList,
} from '../../domain/feeds/feedList';
import { ERROR_MESSAGES } from '../../shared/errorMessage';
import FeedItemList from './FeedItemList';

jest.mock('../../assets/images/loading-animation.webp', () => 'loading.webp');
jest.mock('../../assets/images/empty-person-and-dog.png', () => 'empty.png');
jest.mock('../../assets/images/request-fail.png', () => 'error.png');
jest.mock('../../assets/icons/feed-heart.svg', () => 'feed-heart.svg');
jest.mock('../../assets/icons/feed-comment.svg', () => 'feed-comment.svg');
jest.mock('../../assets/icons/feed-send.svg', () => 'feed-send.svg');
jest.mock('../../assets/icons/feed-more.svg', () => 'feed-more.svg');
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
  category: { id: '1', name: '일상' },
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
  mockGetFeedList.mockReset();
  mockGetFeedList.mockResolvedValue(response);
});

describe('피드 목록 조회 상태', () => {
  it('로딩 후 조회한 항목 수만큼 기존 카드를 표시한다', async (): Promise<void> => {
    mockGetFeedList.mockResolvedValue({
      ...response,
      items: [feed, { ...feed, id: 'second' }],
    });
    render(<FeedItemList categoryId="1" sort="POPULAR" />);

    expect(screen.getByRole('img', { name: '로딩 중' })).toBeInTheDocument();
    expect(await screen.findAllByRole('article')).toHaveLength(2);
    expect(mockGetFeedList).toHaveBeenCalledWith({
      categoryId: '1',
      sort: 'POPULAR',
      size: 20,
      signal: expect.any(AbortSignal),
    });
  });

  it('조회한 반응 수와 카드 액션 UI를 표시한다', async (): Promise<void> => {
    render(<FeedItemList sort="LATEST" />);
    await screen.findByRole('article');

    expect(screen.queryByText('FeedItemActions')).not.toBeInTheDocument();
    expect(
      screen.getByRole('button', { name: '좋아요 12개' }),
    ).toHaveTextContent('12');
    expect(screen.getByRole('button', { name: '댓글 3개' })).toHaveTextContent(
      '3',
    );
    expect(
      screen.getByRole('button', { name: '피드 공유' }),
    ).toBeInTheDocument();
    expect(
      screen.queryByRole('button', { name: '피드 더보기' }),
    ).not.toBeInTheDocument();
  });

  it('본인 피드에만 더보기 버튼을 표시한다', async (): Promise<void> => {
    mockGetFeedList.mockResolvedValue({
      ...response,
      items: [{ ...feed, isMine: true }],
    });
    render(<FeedItemList sort="LATEST" />);

    expect(
      await screen.findByRole('button', { name: '피드 더보기' }),
    ).toBeInTheDocument();
  });

  it('프로필 이미지 로딩에 실패하면 기본 이미지로 대체한다', async () => {
    render(<FeedItemList sort="LATEST" />);
    const article = await screen.findByRole('article');
    const image = article.querySelector('header img');
    if (!image) throw new Error('프로필 이미지가 없습니다.');

    expect(image).toHaveAttribute('src', '/profile.svg');
    fireEvent.error(image);
    expect(image).toHaveAttribute('src', 'profile-fallback.svg');
  });

  it('빈 응답은 FeedEmpty를 표시한다', async (): Promise<void> => {
    mockGetFeedList.mockResolvedValue(emptyResponse);
    render(<FeedItemList sort="LATEST" />);

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
    render(<FeedItemList categoryId="2" sort="LATEST" />);
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
      render(<FeedItemList sort="LATEST" />);

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
    const { rerender } = render(<FeedItemList categoryId="1" sort="LATEST" />);
    const previousSignal = mockGetFeedList.mock.calls[0][0]?.signal;

    rerender(<FeedItemList categoryId="2" sort="POPULAR" />);
    expect(previousSignal?.aborted).toBe(true);
    expect(
      await screen.findByText('아직 이 카테고리에 공유된 피드가 없어요.'),
    ).toBeInTheDocument();
    expect(mockGetFeedList).toHaveBeenLastCalledWith({
      categoryId: '2',
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
