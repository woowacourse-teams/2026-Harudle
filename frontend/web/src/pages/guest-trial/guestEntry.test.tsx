import { StrictMode } from 'react';
import { afterEach, describe, expect, it, jest } from '@jest/globals';
import { render, screen, waitFor } from '@testing-library/react';
import {
  checkGuestEntryAuthentication,
  createGuestEntryInitializer,
  type GuestEntryResult,
} from './guestEntry';
import useGuestEntry from './useGuestEntry';

const mockNavigate = { current: jest.fn() };

jest.mock('react-router', () => ({
  useNavigate: () => mockNavigate.current,
}));

const createResponse = (data: unknown, status: number): Response => {
  return {
    ok: status >= 200 && status < 300,
    status,
    json: () => Promise.resolve(data),
  } as Response;
};

const fetchMock = jest.fn<typeof fetch>();

const GuestEntryProbe = ({
  initialize,
}: {
  initialize: () => Promise<GuestEntryResult>;
}) => {
  const { guestEntryRequest } = useGuestEntry(initialize);

  return <div>{guestEntryRequest.status}</div>;
};

afterEach(() => {
  fetchMock.mockReset();
  mockNavigate.current = jest.fn();
});

describe('게스트 진입 초기화', () => {
  it('Refresh Token이 없으면 비로그인 상태로 판단한다', async () => {
    globalThis.fetch = fetchMock;
    fetchMock
      .mockResolvedValueOnce(createResponse({ token: 'csrf-token' }, 200))
      .mockResolvedValueOnce(
        createResponse(
          {
            type: 'about:blank',
            title: 'Unauthorized',
            status: 401,
            detail: '유효한 Refresh Token이 없습니다.',
            instance: '/api/v1/auth/refresh',
            code: 'INVALID_REFRESH_TOKEN',
            traceId: 'trace-id',
          },
          401,
        ),
      );

    await expect(checkGuestEntryAuthentication()).resolves.toBe(false);
    expect(fetchMock).toHaveBeenNthCalledWith(1, '/api/v1/auth/csrf', {
      credentials: 'include',
    });
    expect(fetchMock).toHaveBeenNthCalledWith(2, '/api/v1/auth/refresh', {
      method: 'POST',
      credentials: 'include',
      headers: {
        'X-XSRF-TOKEN': 'csrf-token',
      },
    });
  });

  it('인증 확인 후 비로그인일 때만 게스트 세션을 발급한다', async () => {
    const executionOrder: string[] = [];
    const checkAuthentication = jest.fn(async () => {
      executionOrder.push('authentication');
      return false;
    });
    const createGuestSession = jest.fn(async () => {
      executionOrder.push('guest-session');
    });
    const initialize = createGuestEntryInitializer({
      checkAuthentication,
      createGuestSession,
    });

    await expect(initialize()).resolves.toEqual({ status: 'guest' });
    expect(executionOrder).toEqual(['authentication', 'guest-session']);
  });

  it('StrictMode에서도 Refresh와 게스트 세션 요청을 한 번씩만 실행한다', async () => {
    const checkAuthentication = jest.fn(async () => false);
    const createGuestSession = jest.fn(async () => {});
    const initialize = createGuestEntryInitializer({
      checkAuthentication,
      createGuestSession,
    });

    render(
      <StrictMode>
        <GuestEntryProbe initialize={initialize} />
      </StrictMode>,
    );

    expect(await screen.findByText('success')).toBeInTheDocument();
    expect(checkAuthentication).toHaveBeenCalledTimes(1);
    expect(createGuestSession).toHaveBeenCalledTimes(1);
  });

  it('초기화 완료 후 다시 진입하면 인증 상태를 새로 확인한다', async () => {
    const checkAuthentication = jest.fn(async () => false);
    const createGuestSession = jest.fn(async () => {});
    const initialize = createGuestEntryInitializer({
      checkAuthentication,
      createGuestSession,
    });

    const firstRequest = initialize();
    const concurrentRequest = initialize();

    expect(firstRequest).toBe(concurrentRequest);
    await firstRequest;
    await initialize();

    expect(checkAuthentication).toHaveBeenCalledTimes(2);
    expect(createGuestSession).toHaveBeenCalledTimes(2);
  });

  it('로그인 사용자는 홈으로 이동하고 게스트 세션을 발급하지 않는다', async () => {
    const checkAuthentication = jest.fn(async () => true);
    const createGuestSession = jest.fn(async () => {});
    const initialize = createGuestEntryInitializer({
      checkAuthentication,
      createGuestSession,
    });

    render(<GuestEntryProbe initialize={initialize} />);

    await waitFor(() => {
      expect(mockNavigate.current).toHaveBeenCalledWith('/', { replace: true });
    });
    expect(createGuestSession).not.toHaveBeenCalled();
  });

  it('하위 경로 이동으로 navigate 함수가 바뀌어도 초기화를 반복하지 않는다', async () => {
    const initialize = jest.fn(async () => ({ status: 'guest' }) as const);
    const { rerender } = render(<GuestEntryProbe initialize={initialize} />);

    expect(await screen.findByText('success')).toBeInTheDocument();
    expect(initialize).toHaveBeenCalledTimes(1);

    mockNavigate.current = jest.fn();
    rerender(<GuestEntryProbe initialize={initialize} />);

    await waitFor(() => {
      expect(screen.getByText('success')).toBeInTheDocument();
    });
    expect(initialize).toHaveBeenCalledTimes(1);
  });
});
