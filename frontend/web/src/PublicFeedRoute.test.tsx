import { describe, expect, it, jest } from '@jest/globals';
import { render, screen } from '@testing-library/react';
import PublicFeedRoute from './PublicFeedRoute';

let mockIsRestoring = true;
jest.mock('./useSessionRestore', () => ({
  __esModule: true,
  default: () => ({ isRestoring: mockIsRestoring }),
}));
jest.mock('react-router', () => ({
  Outlet: () => <div>공개 피드 콘텐츠</div>,
}));
jest.mock('./shared/LoadingSpinner', () => ({
  __esModule: true,
  default: () => <div>세션 확인 중</div>,
}));
describe('공개 피드의 세션 복원', () => {
  it('세션 복원이 끝나기 전에는 콘텐츠를 렌더링하지 않는다', () => {
    mockIsRestoring = true;
    const { rerender } = render(<PublicFeedRoute />);
    expect(screen.getByText('세션 확인 중')).toBeInTheDocument();
    expect(screen.queryByText('공개 피드 콘텐츠')).not.toBeInTheDocument();

    mockIsRestoring = false;
    rerender(<PublicFeedRoute />);
    expect(screen.getByText('공개 피드 콘텐츠')).toBeInTheDocument();
  });

  it('세션 복원이 필요하지 않거나 끝나면 공개 피드를 표시한다', () => {
    mockIsRestoring = false;
    render(<PublicFeedRoute />);
    expect(screen.getByText('공개 피드 콘텐츠')).toBeInTheDocument();
  });
});
