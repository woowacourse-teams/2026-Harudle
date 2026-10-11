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
  getFeedComments,
} from '../../domain/feeds/feedComments';
import type { deleteFeedComment } from '../../domain/feeds/feedCommentDelete';
import FeedDetailPage from './FeedDetailPage';

const mockNavigate = jest.fn();
jest.mock('react-router', () => ({
  useNavigate: () => mockNavigate,
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
jest.mock('../../useEntryStatus', () => ({
  __esModule: true,
  default: () => ({ status: 'home' }),
}));
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
  category: { id: '1', name: '일상' },
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
    const alert = jest
      .spyOn(window, 'alert')
      .mockImplementation((): void => {});
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
    expect(alert).toHaveBeenCalledWith('삭제 실패');
    expect(screen.getByText(comment.content)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '1' })).toBeInTheDocument();

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
