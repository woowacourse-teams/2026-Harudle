import {
  afterEach,
  beforeEach,
  describe,
  expect,
  it,
  jest,
} from '@jest/globals';
import { act, fireEvent, render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import type {
  CategoryListResponse,
  getCategoryList,
} from '../../domain/category/categoryList';
import type { DiaryDetailResponse } from '../../domain/diary/diaryDetail';
import type { Feed } from '../../domain/feeds/feedList';
import type { publishFeed } from '../../domain/feeds/feedPublish';
import FeedPostPage from './FeedPostPage';
import { FEED_POST_COPY } from './copy';

const mockNavigate = jest.fn();
const mockTrack = jest.fn();
jest.mock('../../posthog/useAnalytics', () => ({
  useAnalytics: () => ({ track: mockTrack }),
}));
let mockDiaryId: string | undefined = 'diary-1';
let mockLocationState: unknown;
jest.mock('react-router', () => ({
  useNavigate: () => mockNavigate,
  useParams: () => ({ diaryId: mockDiaryId }),
  useLocation: () => ({ state: mockLocationState }),
}));
jest.mock('../../assets/images/harudle-logo.webp', () => 'logo.webp');
jest.mock('../../assets/images/loading-animation.webp', () => 'loading.webp');
jest.mock('../../assets/icons/back.svg', () => 'back.svg');
jest.mock('../../assets/icons/feed-send.svg', () => 'send.svg');
const mockCaptureError = jest.fn();
jest.mock('../../posthog/useErrorTracking', () => ({
  useErrorTracking: () => ({ captureError: mockCaptureError }),
}));
const mockGetCategoryList = jest.fn<typeof getCategoryList>();
const mockPublishFeed = jest.fn<typeof publishFeed>();
jest.mock('../../domain/category/categoryList', () => ({
  getCategoryList: (
    ...args: Parameters<typeof getCategoryList>
  ): ReturnType<typeof getCategoryList> => mockGetCategoryList(...args),
}));
jest.mock('../../domain/feeds/feedPublish', () => ({
  publishFeed: (
    ...args: Parameters<typeof publishFeed>
  ): ReturnType<typeof publishFeed> => mockPublishFeed(...args),
}));

const diary: DiaryDetailResponse = {
  id: 'diary-1',
  publishedFeedId: null,
  diaryDate: '2026-10-12',
  sourceText: '공개하지 않는 줄거리',
  createdAt: '2026-10-12T10:00:00+09:00',
  generation: {
    id: 'generation-1',
    status: 'SUCCEEDED',
    title: '숨겨야 하는 제목',
    imageUrl: '/comic.webp',
    imageUrlExpiresAt: '2099-10-12T12:00:00+09:00',
    completedAt: '2026-10-12T10:01:00+09:00',
  },
};
const categories: CategoryListResponse = [
  { id: 2, name: '우테코', sortOrder: 1, active: true },
  { id: 1, name: '일상', sortOrder: 0, active: true },
  { id: 3, name: '비활성', sortOrder: 2, active: false },
];
const feed: Feed = {
  id: 'feed-1',
  author: { id: 'user-1', nickname: '하루들', profileImageUrl: '/profile.svg' },
  category: { id: 2, name: '우테코' },
  imageUrl: diary.generation.imageUrl,
  imageUrlExpiresAt: diary.generation.imageUrlExpiresAt,
  publishedAt: '2026-10-12T12:00:00+09:00',
  likeCount: 0,
  commentCount: 0,
  likedByMe: false,
  isMine: true,
  shareUrl: 'https://harudle.com/feeds/feed-1',
};

beforeEach((): void => {
  jest.spyOn(window, 'alert').mockImplementation((): void => {});
  mockDiaryId = diary.id;
  mockLocationState = {
    imageUrl: diary.generation.imageUrl,
    publishedFeedId: null,
  };
  mockGetCategoryList.mockReset().mockResolvedValue(categories);
  mockPublishFeed.mockReset().mockResolvedValue(feed);
  mockNavigate.mockReset();
});
afterEach((): void => {
  jest.restoreAllMocks();
});

describe('피드 게시 화면', (): void => {
  it('전달된 이미지를 표시하고 제목과 줄거리는 표시하지 않는다', async (): Promise<void> => {
    render(<FeedPostPage />);
    expect(
      await screen.findByRole('img', { name: '네컷만화' }),
    ).toHaveAttribute('src', diary.generation.imageUrl);
    expect(
      await screen.findByRole('radio', { name: '우테코' }),
    ).not.toBeChecked();
    expect(screen.getByRole('radio', { name: '일상' })).not.toBeChecked();
    expect(
      screen.getByRole('button', { name: FEED_POST_COPY.publishAction }),
    ).toBeDisabled();
    expect(
      screen
        .getAllByRole('radio')
        .map((radio): string | null => radio.getAttribute('value')),
    ).toEqual(['2', '1']);
    expect(screen.queryByText(diary.generation.title)).not.toBeInTheDocument();
    expect(screen.queryByText(diary.sourceText)).not.toBeInTheDocument();
    expect(
      screen.queryByRole('radio', { name: '비활성' }),
    ).not.toBeInTheDocument();
    const user = userEvent.setup();
    await user.click(screen.getByRole('button', { name: '뒤로가기 아이콘' }));
    expect(mockNavigate).toHaveBeenCalledWith('/diary/diary-1');
  });

  it('카테고리 선택 후 필요한 필드만 게시하고 성공하면 피드로 이동한다', async (): Promise<void> => {
    const user = userEvent.setup();
    render(<FeedPostPage />);
    await user.click(await screen.findByRole('radio', { name: '일상' }));
    await user.click(
      screen.getByRole('button', { name: FEED_POST_COPY.publishAction }),
    );
    expect(mockPublishFeed).toHaveBeenCalledWith({
      diaryId: diary.id,
      categoryId: 1,
    });
    expect(mockNavigate).toHaveBeenCalledWith('/', { replace: true });
    expect(mockTrack).toHaveBeenCalledWith(
      'api_requested',
      expect.objectContaining({
        operation: 'feed_publish',
        diary_id: diary.id,
        category_id: 1,
      }),
    );
    expect(mockTrack).toHaveBeenCalledTimes(1);
    expect(JSON.stringify(mockTrack.mock.calls)).not.toContain(
      diary.sourceText,
    );
  });

  it('게시 중 중복 제출을 막고 실패 후 선택을 유지한 채 재시도한다', async (): Promise<void> => {
    let rejectPublish: ((error: Error) => void) | undefined;
    mockPublishFeed.mockImplementationOnce(
      (): Promise<Feed> =>
        new Promise((_, reject): void => {
          rejectPublish = reject;
        }),
    );
    const user = userEvent.setup();
    render(<FeedPostPage />);
    await user.click(await screen.findByRole('radio', { name: '우테코' }));
    const button = screen.getByRole('button', {
      name: FEED_POST_COPY.publishAction,
    });
    await user.click(button);
    expect(button).toBeDisabled();
    const form = button.closest('form');
    if (!form) throw new Error('게시 폼이 없습니다.');
    fireEvent.submit(form);
    expect(mockPublishFeed).toHaveBeenCalledTimes(1);
    await act(async (): Promise<void> => {
      rejectPublish?.(new Error('실패'));
    });
    expect(window.alert).toHaveBeenCalledTimes(1);
    expect(window.alert).toHaveBeenCalledWith(
      '피드에 올리지 못했어요. 다시 시도해 주세요.',
    );
    expect(screen.getByRole('radio', { name: '우테코' })).toBeChecked();
    await user.click(button);
    expect(mockPublishFeed).toHaveBeenCalledTimes(2);
    expect(mockNavigate).toHaveBeenCalledWith('/', { replace: true });
  });

  it('카테고리 조회 실패는 다시 불러올 수 있고 빈 목록에서는 게시를 막는다', async (): Promise<void> => {
    mockGetCategoryList
      .mockRejectedValueOnce(new Error('조회 실패'))
      .mockResolvedValueOnce([]);
    const user = userEvent.setup();
    render(<FeedPostPage />);
    await screen.findByText(
      '카테고리를 불러오지 못했어요. 다시 시도해 주세요.',
    );
    expect(
      screen.getByRole('button', { name: FEED_POST_COPY.publishAction }),
    ).toBeDisabled();
    await user.click(
      screen.getByRole('button', { name: FEED_POST_COPY.retryAction }),
    );
    expect(
      await screen.findByText(FEED_POST_COPY.noCategories),
    ).toBeInTheDocument();
    expect(
      screen.getByRole('button', { name: FEED_POST_COPY.publishAction }),
    ).toBeDisabled();
  });

  it.each([
    null,
    undefined,
    {},
    { imageUrl: '/comic.webp' },
    { imageUrl: '', publishedFeedId: null },
    { imageUrl: 123, publishedFeedId: null },
    { imageUrl: '/comic.webp', publishedFeedId: 'feed-1' },
  ])(
    '전달값이 없거나 유효하지 않거나 이미 게시됐다면 게시를 막는다 (%p)',
    (state): void => {
      mockLocationState = state;
      render(<FeedPostPage />);
      expect(screen.getByText(FEED_POST_COPY.invalidEntry)).toBeInTheDocument();
      expect(
        screen.queryByRole('img', { name: '네컷만화' }),
      ).not.toBeInTheDocument();
      expect(mockGetCategoryList).not.toHaveBeenCalled();
      expect(mockPublishFeed).not.toHaveBeenCalled();
    },
  );

  it('일기 ID가 없으면 게시를 막는다', (): void => {
    mockDiaryId = undefined;
    render(<FeedPostPage />);
    expect(screen.getByText(FEED_POST_COPY.invalidEntry)).toBeInTheDocument();
    expect(mockPublishFeed).not.toHaveBeenCalled();
  });
});
