import { describe, expect, it, jest } from '@jest/globals';
import type { ReactNode } from 'react';
import { render, screen } from '@testing-library/react';
import LandingRoutes from './LandingRoutes';

let mockPath = '/';
jest.mock('react-router', () => ({
  Route: ({
    index,
    path,
    element,
  }: {
    index?: boolean;
    path?: string;
    element: ReactNode;
  }) => (
    <>
      {(index && mockPath === '/') ||
      (path === 'result/:diaryId' && mockPath === '/result/123')
        ? element
        : null}
    </>
  ),
  Routes: ({ children }: { children: ReactNode }) => <>{children}</>,
}));

const mockUseGuestEntry = jest.fn(() => ({
  guestEntryRequest: { status: 'success' as const, data: undefined },
  retryGuestEntry: jest.fn(),
}));

jest.mock('./useGuestEntry', () => ({
  __esModule: true,
  default: () => mockUseGuestEntry(),
}));
jest.mock('./LandingContent', () => () => <p>하루들 랜딩</p>);
jest.mock('../../assets/images/loading-animation.webp', () => 'loading.webp');
jest.mock('./GuestDiaryResultPage', () => () => <p>게스트 결과</p>);
jest.mock('../not-found/NotFoundPage', () => () => <p>없는 페이지</p>);

describe('랜딩 경로', () => {
  it('랜딩 진입 시 게스트 세션을 준비하지 않는다', () => {
    mockUseGuestEntry.mockClear();
    mockPath = '/';
    render(<LandingRoutes />);
    expect(screen.getByText('하루들 랜딩')).toBeInTheDocument();
    expect(mockUseGuestEntry).not.toHaveBeenCalled();
  });

  it('기존 게스트 결과 경로는 그대로 준비한다', () => {
    mockUseGuestEntry.mockClear();
    mockPath = '/result/123';
    render(<LandingRoutes />);
    expect(screen.getByText('게스트 결과')).toBeInTheDocument();
    expect(mockUseGuestEntry).toHaveBeenCalled();
  });
});
