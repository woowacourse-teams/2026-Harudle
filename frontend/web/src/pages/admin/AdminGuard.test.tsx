import { render, screen, waitFor } from '@testing-library/react';
import { beforeEach, describe, expect, it, jest } from '@jest/globals';
import AdminGuard from './AdminGuard';

const mockAuthFetch = jest.fn<(...args: unknown[]) => Promise<Response>>();

jest.mock('../../shared/auth', () => ({
  authFetch: (...args: unknown[]) => mockAuthFetch(...args),
}));

jest.mock('./AdminRoutes', () => ({
  __esModule: true,
  default: () => <div>관리자 페이지</div>,
}));

jest.mock('react-router', () => ({
  Navigate: ({ to }: { to: string }) => <div data-testid="navigate">{to}</div>,
}));

const jsonResponse = (body: unknown, status = 200): Response =>
  ({
    ok: status >= 200 && status < 300,
    status,
    json: async () => body,
  }) as Response;

describe('AdminGuard', () => {
  beforeEach(() => {
    window.localStorage.clear();
    mockAuthFetch.mockReset();
  });

  it('로그인하지 않은 사용자는 관리자 페이지 대신 로그인으로 이동한다', async () => {
    render(<AdminGuard />);

    await waitFor(() => {
      expect(screen.getByTestId('navigate')).toHaveTextContent('/login');
    });
    expect(mockAuthFetch).not.toHaveBeenCalled();
    expect(screen.queryByText('관리자 페이지')).not.toBeInTheDocument();
  });

  it('USER 권한 사용자는 관리자 페이지 대신 홈으로 이동한다', async () => {
    window.localStorage.setItem('harudle.has-completed-oauth', 'true');
    mockAuthFetch.mockResolvedValueOnce(jsonResponse({ role: 'USER' }));

    render(<AdminGuard />);

    await waitFor(() => {
      expect(screen.getByTestId('navigate')).toHaveTextContent('/');
    });
    expect(screen.queryByText('관리자 페이지')).not.toBeInTheDocument();
  });

  it('ADMIN 권한 사용자만 관리자 페이지를 볼 수 있다', async () => {
    window.localStorage.setItem('harudle.has-completed-oauth', 'true');
    mockAuthFetch.mockResolvedValueOnce(jsonResponse({ role: 'ADMIN' }));

    render(<AdminGuard />);

    expect(await screen.findByText('관리자 페이지')).toBeInTheDocument();
    expect(screen.queryByTestId('navigate')).not.toBeInTheDocument();
  });
});
