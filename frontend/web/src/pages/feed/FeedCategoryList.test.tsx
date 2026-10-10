import { beforeEach, describe, expect, it, jest } from '@jest/globals';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import type {
  CategoryListResponse,
  getCategoryList,
} from '../../domain/category/categoryList';
import FeedCategoryList, {
  type FeedCategoryListProps,
} from './FeedCategoryList';

const categories: CategoryListResponse = [
  { id: '1', name: '일상', sortOrder: 0, active: true },
  { id: '2', name: '우테코', sortOrder: 1, active: true },
  { id: '3', name: '새 카테고리', sortOrder: 2, active: true },
];

const mockGetCategoryList = jest.fn<typeof getCategoryList>();

jest.mock('../../assets/icons/autorenew.svg', () => 'autorenew.svg');

jest.mock('../../domain/category/categoryList', () => ({
  getCategoryList: (
    ...args: Parameters<typeof getCategoryList>
  ): ReturnType<typeof getCategoryList> => mockGetCategoryList(...args),
}));

beforeEach((): void => {
  mockGetCategoryList.mockReset();
  mockGetCategoryList.mockResolvedValue(categories);
});

describe('피드 카테고리 목록', () => {
  it('조회 실패를 표시하고 다시 시도하면 목록을 표시한다', async (): Promise<void> => {
    const user = userEvent.setup();
    mockGetCategoryList.mockRejectedValueOnce(new Error('조회 실패'));
    render(
      <FeedCategoryList
        selectedCategoryId={undefined}
        onCategoryChange={jest.fn()}
      />,
    );

    expect(
      await screen.findByText(/카테고리를 불러오지 못했어요/),
    ).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: '다시 시도' }));
    expect(
      await screen.findByRole('button', { name: '새 카테고리' }),
    ).toBeInTheDocument();
    expect(mockGetCategoryList).toHaveBeenCalledTimes(2);
  });

  it('조회한 카테고리와 전체 조회 항목을 표시한다', async (): Promise<void> => {
    render(
      <FeedCategoryList
        selectedCategoryId={undefined}
        onCategoryChange={jest.fn()}
      />,
    );

    expect(
      await screen.findByRole('button', { name: '새 카테고리' }),
    ).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '전체' })).toBeInTheDocument();
    expect(screen.getAllByRole('button')).toHaveLength(4);
  });

  it('선택 시 ID를 전달하고 전체 선택 시 undefined를 전달한다', async (): Promise<void> => {
    const user = userEvent.setup();
    const onCategoryChange =
      jest.fn<FeedCategoryListProps['onCategoryChange']>();
    render(
      <FeedCategoryList
        selectedCategoryId="2"
        onCategoryChange={onCategoryChange}
      />,
    );

    await user.click(await screen.findByRole('button', { name: '일상' }));
    expect(onCategoryChange).toHaveBeenLastCalledWith('1');
    await user.click(screen.getByRole('button', { name: '전체' }));
    expect(onCategoryChange).toHaveBeenLastCalledWith(undefined);
  });

  it('선택 ID가 바뀌어도 카테고리 목록을 다시 조회하지 않는다', async (): Promise<void> => {
    const onCategoryChange = jest.fn();
    const { rerender } = render(
      <FeedCategoryList
        selectedCategoryId="2"
        onCategoryChange={onCategoryChange}
      />,
    );
    await screen.findByRole('button', { name: '우테코' });

    rerender(
      <FeedCategoryList
        selectedCategoryId="1"
        onCategoryChange={onCategoryChange}
      />,
    );
    expect(screen.getByRole('button', { name: '일상' })).toBeInTheDocument();
    expect(mockGetCategoryList).toHaveBeenCalledTimes(1);
  });
});
