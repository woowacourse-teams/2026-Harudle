import {
  afterEach,
  beforeEach,
  describe,
  expect,
  it,
  jest,
} from '@jest/globals';
import { act, fireEvent, render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import type { Feed } from '../../domain/feeds/feedList';
import type { getFeedDetail } from '../../domain/feeds/feedDetail';
import type {
  FeedComment,
  FeedCommentsResponse,
  getFeedComments,
} from '../../domain/feeds/feedComments';
import type { deleteFeedComment } from '../../domain/feeds/feedCommentDelete';
import FeedDetailPage from './FeedDetailPage';
import FeedDetailContent from './FeedDetailContent';

const mockAddToast = jest.fn();
jest.mock('../../shared/ToastContext', () => ({
  useToastContext: () => ({ addToast: mockAddToast }),
}));

const mockNavigate = jest.fn();
let mockLocationState: unknown = null;
const mockTrack = jest.fn();
jest.mock('../../posthog/useAnalytics', () => ({
  useAnalytics: () => ({ track: mockTrack }),
}));
jest.mock('react-router', () => ({
  useNavigate: () => mockNavigate,
  useLocation: () => ({ state: mockLocationState }),
  useParams: () => ({ feedId: 'feed-1' }),
}));
jest.mock('../../assets/images/harudle-logo.webp', () => 'logo.webp');
jest.mock('../../assets/images/loading-animation.webp', () => 'loading.webp');
jest.mock('../../assets/images/request-fail.png', () => 'request-fail.png');
jest.mock('../../assets/images/profile-fallback.svg', () => 'fallback.svg');
jest.mock('../../assets/icons/back.svg', () => 'back.svg');
jest.mock('../../assets/icons/feed-category-chevron.svg', () => 'chevron.svg');
jest.mock('../../assets/icons/feed-more.svg', () => 'more.svg');
jest.mock('../../assets/icons/feed-heart.svg', () => 'heart.svg');
jest.mock('../../assets/icons/feed-heart-filled.svg', () => 'heart-filled.svg');
jest.mock('../../assets/icons/feed-comment.svg', () => 'comment.svg');
jest.mock('../../assets/icons/feed-comment-submit.svg', () => 'send.svg');
jest.mock('../../assets/icons/feed-send.svg', () => 'share.svg');
jest.mock('../../assets/icons/kakao.svg', () => 'kakao.svg');
jest.mock('../../assets/icons/link.svg', () => 'link.svg');
jest.mock('../../assets/icons/check.svg', () => 'check.svg');
jest.mock('../../assets/icons/delete.svg', () => 'delete.svg');
jest.mock('../../assets/icons/bottom-sheet-close.svg', () => 'close.svg');
const mockCaptureError = jest.fn();
jest.mock('../../posthog/useErrorTracking', () => ({
  useErrorTracking: () => ({ captureError: mockCaptureError }),
}));
const mockGetFeedDetail = jest.fn<typeof getFeedDetail>();
const mockGetFeedComments = jest.fn<typeof getFeedComments>();
const mockDeleteComment = jest.fn<typeof deleteFeedComment>();
jest.mock('../../domain/feeds/feedDetail', () => ({
  getFeedDetail: (
    ...args: Parameters<typeof getFeedDetail>
  ): ReturnType<typeof getFeedDetail> => mockGetFeedDetail(...args),
}));
jest.mock('../../domain/feeds/feedComments', () => ({
  getFeedComments: (
    ...args: Parameters<typeof getFeedComments>
  ): ReturnType<typeof getFeedComments> => mockGetFeedComments(...args),
}));
jest.mock('../../domain/feeds/feedCommentDelete', () => ({
  deleteFeedComment: (
    ...args: Parameters<typeof deleteFeedComment>
  ): ReturnType<typeof deleteFeedComment> => mockDeleteComment(...args),
}));

const feed: Feed = {
  id: 'feed-1',
  author: { id: 'user-1', nickname: '네오', profileImageUrl: '/profile.svg' },
  category: { id: 1, name: '일상' },
  imageUrl: '/comic.webp',
  imageUrlExpiresAt: '2099-10-11T12:00:00+09:00',
  publishedAt: '2026-10-11T12:00:00+09:00',
  likeCount: 12,
  likedByMe: false,
  commentCount: 1,
  isMine: false,
  shareUrl: 'https://harudle.com/feeds/feed-1',
};
const comment: FeedComment = {
  id: 'comment-1',
  author: feed.author,
  content: '첫 댓글',
  createdAt: '2026-10-11T12:00:00+09:00',
  isMine: true,
};

const prepareDialogs = (): void => {
  document.querySelectorAll('dialog').forEach((dialog): void => {
    Object.defineProperties(dialog, {
      showModal: { value: (): void => dialog.setAttribute('open', '') },
      close: { value: (): void => dialog.removeAttribute('open') },
    });
  });
};

const openDelete = async (
  user: ReturnType<typeof userEvent.setup>,
): Promise<void> => {
  const row = screen.getByText(comment.content).closest('li');
  if (!row) throw new Error('댓글이 없습니다.');
  await user.click(within(row).getByRole('button'));
  await user.click(screen.getByRole('button', { name: /댓글 삭제$/ }));
};

beforeEach((): void => {
  mockLocationState = null;
  localStorage.clear();
  mockGetFeedDetail.mockReset().mockResolvedValue(feed);
  mockGetFeedComments
    .mockReset()
    .mockResolvedValue({ items: [comment], nextCursor: null, hasNext: false });
  mockDeleteComment.mockReset();
});

afterEach((): void => {
  jest.restoreAllMocks();
});

describe('피드 상세 뒤로가기', (): void => {
  it.each([false, true])(
    '목록에서 진입하면 이전 화면으로 돌아간다 (조회 실패: %s)',
    async (isError): Promise<void> => {
      mockLocationState = { fromFeed: true };
      if (isError)
        mockGetFeedDetail.mockRejectedValueOnce(new Error('조회 실패'));
      const user = userEvent.setup();
      render(<FeedDetailPage />);

      await user.click(
        await screen.findByRole('button', { name: '뒤로가기 아이콘' }),
      );

      expect(mockNavigate).toHaveBeenCalledWith(-1);
    },
  );

  it.each([false, true])(
    '직접 진입하면 상세 기록을 홈으로 교체한다 (조회 실패: %s)',
    async (isError): Promise<void> => {
      if (isError)
        mockGetFeedDetail.mockRejectedValueOnce(new Error('조회 실패'));
      const user = userEvent.setup();
      render(<FeedDetailPage />);

      await user.click(
        await screen.findByRole('button', { name: '뒤로가기 아이콘' }),
      );

      expect(mockNavigate).toHaveBeenCalledWith('/', { replace: true });
    },
  );
});

describe('피드 상세 댓글 더 보기', (): void => {
  const firstPage: FeedCommentsResponse = {
    items: [comment],
    nextCursor: 'cursor-1',
    hasNext: true,
  };
  const secondComment: FeedComment = {
    ...comment,
    id: 'comment-2',
    content: '두 번째 댓글',
    isMine: false,
  };

  it('서버 커서로 목록을 이어 붙이고 마지막 페이지에서 버튼을 숨긴다', async (): Promise<void> => {
    const user = userEvent.setup();
    mockGetFeedComments
      .mockResolvedValueOnce(firstPage)
      .mockResolvedValueOnce({
        items: [secondComment],
        nextCursor: 'cursor-2',
        hasNext: true,
      })
      .mockResolvedValueOnce({
        items: [{ ...secondComment, id: 'comment-3', content: '세 번째 댓글' }],
        nextCursor: null,
        hasNext: false,
      });
    render(<FeedDetailPage />);

    await user.click(await screen.findByRole('button', { name: '더 보기' }));
    expect(await screen.findByText(secondComment.content)).toBeInTheDocument();
    expect(screen.getByText(comment.content)).toBeInTheDocument();
    expect(screen.getAllByRole('listitem')).toHaveLength(2);
    expect(mockGetFeedComments).toHaveBeenNthCalledWith(2, {
      feedId: feed.id,
      cursor: 'cursor-1',
      size: 50,
      signal: expect.any(AbortSignal),
    });

    await user.click(screen.getByRole('button', { name: '더 보기' }));
    expect(await screen.findByText('세 번째 댓글')).toBeInTheDocument();
    expect(mockGetFeedComments).toHaveBeenNthCalledWith(3, {
      feedId: feed.id,
      cursor: 'cursor-2',
      size: 50,
      signal: expect.any(AbortSignal),
    });
    expect(screen.getAllByRole('listitem')).toHaveLength(3);
    expect(
      screen.queryByRole('button', { name: '더 보기' }),
    ).not.toBeInTheDocument();
    expect(mockGetFeedDetail).toHaveBeenCalledTimes(1);
    expect(mockTrack).toHaveBeenCalledWith(
      'api_requested',
      expect.objectContaining({
        operation: 'feed_comments',
        feed_id: feed.id,
        request_reason: 'first_page',
      }),
    );
    expect(mockTrack).toHaveBeenCalledWith(
      'api_requested',
      expect.objectContaining({
        operation: 'feed_comments',
        feed_id: feed.id,
        request_reason: 'load_more',
      }),
    );
    expect(mockTrack).toHaveBeenCalledWith(
      'api_requested',
      expect.objectContaining({ operation: 'feed_detail', feed_id: feed.id }),
    );
  });

  it('실패는 컴포넌트에서 알리고 기존 댓글을 유지한 채 같은 커서로 재시도한다', async (): Promise<void> => {
    const user = userEvent.setup();
    mockGetFeedComments
      .mockResolvedValueOnce(firstPage)
      .mockRejectedValueOnce(new Error('추가 조회 실패'))
      .mockResolvedValueOnce({
        items: [secondComment],
        nextCursor: null,
        hasNext: false,
      });
    render(<FeedDetailPage />);

    await user.click(await screen.findByRole('button', { name: '더 보기' }));
    expect(mockAddToast).toHaveBeenCalledWith(
      {
        type: 'error',
        message: '댓글을 더 불러오지 못했어요. 다시 시도해 주세요.',
      },
      2000,
    );
    expect(screen.getByText(comment.content)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '더 보기' })).toBeEnabled();

    await user.click(screen.getByRole('button', { name: '더 보기' }));
    expect(await screen.findByText(secondComment.content)).toBeInTheDocument();
    expect(mockGetFeedComments.mock.calls[1][0].cursor).toBe('cursor-1');
    expect(mockGetFeedComments.mock.calls[2][0].cursor).toBe('cursor-1');
    expect(mockAddToast).toHaveBeenCalledTimes(1);
  });

  it('추가 조회 중 삭제한 댓글을 유지하고 중복 클릭을 막으며 새 댓글을 추가한다', async (): Promise<void> => {
    const user = userEvent.setup();
    let resolveNext: ((page: FeedCommentsResponse) => void) | undefined;
    mockGetFeedComments.mockResolvedValueOnce(firstPage).mockImplementationOnce(
      (): Promise<FeedCommentsResponse> =>
        new Promise((resolve): void => {
          resolveNext = resolve;
        }),
    );
    mockDeleteComment.mockResolvedValueOnce(undefined);
    render(<FeedDetailPage />);

    await user.click(await screen.findByRole('button', { name: '더 보기' }));
    const pendingButton = screen.getByRole('button', {
      name: '불러오는 중...',
    });
    expect(pendingButton).toBeDisabled();
    fireEvent.click(pendingButton);
    expect(mockGetFeedComments).toHaveBeenCalledTimes(2);
    expect(screen.getByText(comment.content)).toBeInTheDocument();
    prepareDialogs();
    await openDelete(user);
    await user.click(screen.getByRole('button', { name: /댓글 삭제$/ }));
    expect(screen.queryByText(comment.content)).not.toBeInTheDocument();
    expect(
      screen.queryByText('첫 번째 댓글을 남겨보세요.'),
    ).not.toBeInTheDocument();
    expect(pendingButton).toBeDisabled();

    await act(async (): Promise<void> => {
      resolveNext?.({
        items: [secondComment],
        nextCursor: null,
        hasNext: false,
      });
    });
    expect(screen.getByText(secondComment.content)).toBeInTheDocument();
    expect(screen.queryByText(comment.content)).not.toBeInTheDocument();
    expect(screen.getAllByRole('listitem')).toHaveLength(1);
    expect(screen.getByRole('button', { name: '0' })).toBeInTheDocument();
  });

  it('피드 전환 시 추가 조회를 취소하고 새 댓글 커서로 시작한다', async (): Promise<void> => {
    const user = userEvent.setup();
    let resolvePrevious: ((page: FeedCommentsResponse) => void) | undefined;
    mockGetFeedComments
      .mockResolvedValueOnce(firstPage)
      .mockImplementationOnce(
        (): Promise<FeedCommentsResponse> =>
          new Promise((resolve): void => {
            resolvePrevious = resolve;
          }),
      )
      .mockResolvedValueOnce({
        items: [secondComment],
        nextCursor: 'new-cursor',
        hasNext: true,
      })
      .mockResolvedValueOnce({ items: [], nextCursor: null, hasNext: false });
    const { rerender } = render(<FeedDetailContent feedId={feed.id} />);
    await user.click(await screen.findByRole('button', { name: '더 보기' }));
    const previousSignal = mockGetFeedComments.mock.calls[1][0].signal;

    rerender(<FeedDetailContent feedId="feed-2" />);
    expect(previousSignal?.aborted).toBe(true);
    expect(await screen.findByText(secondComment.content)).toBeInTheDocument();
    expect(mockGetFeedComments).toHaveBeenNthCalledWith(3, {
      feedId: 'feed-2',
      size: 50,
      signal: expect.any(AbortSignal),
    });

    await act(async (): Promise<void> => {
      resolvePrevious?.({ items: [comment], nextCursor: null, hasNext: false });
    });
    expect(screen.queryByText(comment.content)).not.toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: '더 보기' }));
    expect(mockGetFeedComments).toHaveBeenLastCalledWith({
      feedId: 'feed-2',
      cursor: 'new-cursor',
      size: 50,
      signal: expect.any(AbortSignal),
    });
  });
});

describe('피드 상세 댓글 삭제', (): void => {
  it('다른 사용자의 댓글에는 삭제 메뉴를 표시하지 않는다', async (): Promise<void> => {
    mockGetFeedComments.mockResolvedValue({
      items: [{ ...comment, isMine: false }],
      nextCursor: null,
      hasNext: false,
    });
    render(<FeedDetailPage />);
    const row = (await screen.findByText(comment.content)).closest('li');
    if (!row) throw new Error('댓글이 없습니다.');
    expect(within(row).queryByRole('button')).not.toBeInTheDocument();
  });

  it('취소·실패는 댓글과 개수를 유지하고 재시도 성공 후 갱신한다', async (): Promise<void> => {
    mockDeleteComment
      .mockRejectedValueOnce(new Error('삭제 실패'))
      .mockResolvedValueOnce(undefined);
    const user = userEvent.setup();
    render(<FeedDetailPage />);
    await screen.findByText(comment.content);
    prepareDialogs();
    await openDelete(user);
    await user.click(screen.getByRole('button', { name: '취소' }));
    expect(mockDeleteComment).not.toHaveBeenCalled();
    expect(screen.getByText(comment.content)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '1' })).toBeInTheDocument();

    await openDelete(user);
    await user.click(screen.getByRole('button', { name: /댓글 삭제$/ }));
    expect(
      within(screen.getByRole('dialog')).getByRole('alert'),
    ).toHaveTextContent('삭제 실패');
    expect(screen.getByText(comment.content)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '1' })).toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: '취소' }));
    await openDelete(user);
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: /댓글 삭제$/ }));
    expect(
      await screen.findByText('첫 번째 댓글을 남겨보세요.'),
    ).toBeInTheDocument();
    expect(screen.queryByText(comment.content)).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: '0' })).toBeInTheDocument();
    expect(mockDeleteComment).toHaveBeenCalledTimes(2);
    expect(mockDeleteComment).toHaveBeenLastCalledWith({
      commentId: comment.id,
    });
  });

  it('삭제 응답을 기다리는 동안 중복 요청과 개수 변경을 막는다', async (): Promise<void> => {
    let resolveDelete: (() => void) | undefined;
    mockDeleteComment.mockImplementation(
      (): Promise<void> =>
        new Promise((resolve): void => {
          resolveDelete = resolve;
        }),
    );
    const user = userEvent.setup();
    render(<FeedDetailPage />);
    await screen.findByText(comment.content);
    prepareDialogs();
    await openDelete(user);
    await user.click(screen.getByRole('button', { name: /댓글 삭제$/ }));
    const pendingButton = screen.getByRole('button', { name: '삭제 중...' });
    fireEvent.click(pendingButton);
    fireEvent.click(pendingButton);
    expect(mockDeleteComment).toHaveBeenCalledTimes(1);
    expect(screen.getByText(comment.content)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '1' })).toBeInTheDocument();

    await act(async (): Promise<void> => {
      resolveDelete?.();
    });
    expect(
      await screen.findByText('첫 번째 댓글을 남겨보세요.'),
    ).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '0' })).toBeInTheDocument();
  });
});
