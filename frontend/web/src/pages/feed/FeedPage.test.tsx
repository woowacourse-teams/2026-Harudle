import { describe, expect, it, jest } from '@jest/globals';
import { render, screen } from '@testing-library/react';
import FeedPage from './FeedPage';
import type useEntryStatus from '../../useEntryStatus';
import type { ReactNode } from 'react';

let mockStatus: ReturnType<typeof useEntryStatus>['status'] =
  'restoringSession';
jest.mock('../../useEntryStatus', () => ({
  __esModule: true,
  default: () => ({ status: mockStatus }),
}));
jest.mock('react-router', () => ({
  useSearchParams: () => [new URLSearchParams(), jest.fn()],
}));
jest.mock('../../shared/BottomNavigationLayout', () => ({
  __esModule: true,
  default: ({ children }: { children: ReactNode }) => <div>{children}</div>,
}));
jest.mock('../../shared/LoadingSpinner', () => ({
  __esModule: true,
  default: () => <div>세션 확인 중</div>,
}));
jest.mock('./FeedFilterBar', () => ({
  __esModule: true,
  default: () => <div>피드 필터</div>,
}));
jest.mock('./FeedItemList', () => ({
  __esModule: true,
  default: () => <div>피드 목록</div>,
}));

describe('공개 피드의 세션 복원', () => {
  it('세션 복원이 끝나기 전에는 목록을 조회하지 않는다', () => {
    mockStatus = 'restoringSession';
    const { rerender } = render(<FeedPage />);
    expect(screen.getByText('세션 확인 중')).toBeInTheDocument();
    expect(screen.queryByText('피드 목록')).not.toBeInTheDocument();

    mockStatus = 'home';
    rerender(<FeedPage />);
    expect(screen.getByText('피드 목록')).toBeInTheDocument();
  });

  it.each(['landing', 'login', 'error'] as const)(
    '%s 상태에서도 공개 피드를 표시한다',
    (status) => {
      mockStatus = status;
      render(<FeedPage />);
      expect(screen.getByText('피드 목록')).toBeInTheDocument();
    },
  );
});
