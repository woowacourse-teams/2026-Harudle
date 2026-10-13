import { beforeEach, describe, expect, it, jest } from '@jest/globals';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import type { ReactNode } from 'react';
import type { CategoryListResponse } from '../../domain/category/categoryList';
import FeedPage from './FeedPage';

const mockTrack = jest.fn();
let mockSearchParams = new URLSearchParams();
const mockSetSearchParams =
  jest.fn<(update: (previous: URLSearchParams) => URLSearchParams) => void>();

jest.mock('react-router', () => ({
  useSearchParams: () => [mockSearchParams, mockSetSearchParams],
}));
jest.mock('../../posthog/useAnalytics', () => ({
  useAnalytics: () => ({ track: mockTrack }),
}));
jest.mock('../../shared/BottomNavigationLayout', () => ({
  __esModule: true,
  default: ({ children }: { children: ReactNode }) => <div>{children}</div>,
}));
jest.mock('./FeedItemList', () => ({
  __esModule: true,
  default: ({
    onCategoryClick,
  }: {
    onCategoryClick: (categoryId: number, categoryName: string) => void;
  }) => (
    <button onClick={() => onCategoryClick(2, '우테코')}>카드 카테고리</button>
  ),
}));
jest.mock('../../assets/icons/feed-sort-down.svg', () => 'sort.svg');
jest.mock('../../assets/icons/autorenew.svg', () => 'retry.svg');
jest.mock('../../domain/category/categoryList', () => ({
  getCategoryList: async (): Promise<CategoryListResponse> => [
    { id: 1, name: '일상', sortOrder: 0, active: true },
    { id: 2, name: '우테코', sortOrder: 1, active: true },
  ],
}));

beforeEach((): void => {
  mockSearchParams = new URLSearchParams();
  mockSetSearchParams.mockImplementation((update): void => {
    mockSearchParams = update(mockSearchParams);
  });
});

describe('클라이언트 카테고리 변경 이벤트', (): void => {
  it('필터 선택과 카드 카테고리 선택에 이전 ID와 선택한 이름을 기록한다', async (): Promise<void> => {
    const user = userEvent.setup();
    const { rerender } = render(<FeedPage />);
    await user.click(await screen.findByRole('button', { name: '일상' }));
    expect(mockTrack).toHaveBeenLastCalledWith('feed_category_changed', {
      feature: 'feed',
      operation: 'feed_category_change',
      previous_category_id: null,
      category_id: 1,
      category_name: '일상',
    });
    rerender(<FeedPage />);
    await user.click(screen.getByRole('button', { name: '카드 카테고리' }));
    expect(mockTrack).toHaveBeenLastCalledWith('feed_category_changed', {
      feature: 'feed',
      operation: 'feed_category_change',
      previous_category_id: 1,
      category_id: 2,
      category_name: '우테코',
    });
    rerender(<FeedPage />);
    await user.click(screen.getByRole('button', { name: '전체' }));
    expect(mockTrack).toHaveBeenLastCalledWith('feed_category_changed', {
      feature: 'feed',
      operation: 'feed_category_change',
      previous_category_id: 2,
      category_id: null,
      category_name: '전체',
    });
    expect(mockTrack).toHaveBeenCalledTimes(3);
  });

  it('초기 URL의 카테고리, 같은 카테고리 재선택과 정렬 변경은 변경 이벤트로 세지 않는다', async (): Promise<void> => {
    mockSearchParams = new URLSearchParams('categoryId=1');
    const user = userEvent.setup();
    render(<FeedPage />);
    await user.click(await screen.findByRole('button', { name: '일상' }));
    expect(mockSetSearchParams).not.toHaveBeenCalled();
    await user.selectOptions(screen.getByRole('combobox'), 'POPULAR');
    expect(mockSetSearchParams).toHaveBeenCalledTimes(1);
    expect(mockTrack).not.toHaveBeenCalled();
  });
});
