import {
  afterEach,
  beforeEach,
  describe,
  expect,
  it,
  jest,
} from '@jest/globals';
import type { ReactNode } from 'react';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import LandingRoutes from './LandingRoutes';
import type { ApiRequest } from '../../shared/api';

jest.mock('react-router', () => ({
  Route: ({ index, element }: { index?: boolean; element: ReactNode }) => (
    <>{index ? element : null}</>
  ),
  Routes: ({ children }: { children: ReactNode }) => <>{children}</>,
}));

const mockRetryGuestEntry = jest.fn();
const mockGuestEntryRequest = {
  current: {
    status: 'error',
    error: new Error('게스트 세션 응답 형식이 일치하지 않습니다'),
  } as ApiRequest<void>,
};

jest.mock('./useGuestEntry', () => ({
  __esModule: true,
  default: () => ({
    guestEntryRequest: mockGuestEntryRequest.current,
    retryGuestEntry: mockRetryGuestEntry,
  }),
}));

jest.mock('./GuestDiaryResultPage', () => () => null);
jest.mock(
  './LandingPage',
  () =>
    ({ entryFeedback }: { entryFeedback: ReactNode }) => (
      <>
        <h1>하루들 랜딩</h1>
        {entryFeedback}
      </>
    ),
);
jest.mock('../not-found/NotFoundPage', () => () => <p>없는 페이지</p>);

jest.mock(
  '../../assets/images/loading-animation.webp',
  () => 'loading-animation.webp',
);

beforeEach(() => {
  mockRetryGuestEntry.mockReset();
  mockGuestEntryRequest.current = {
    status: 'error',
    error: new Error('게스트 세션 응답 형식이 일치하지 않습니다'),
  };
  jest.spyOn(console, 'error').mockImplementation(() => undefined);
});

afterEach(() => {
  jest.restoreAllMocks();
});

describe('게스트 체험 라우트', () => {
  it('진입 실패의 내부 메시지는 기록하고 사용자에게 재시도를 제공한다', async () => {
    const user = userEvent.setup();
    render(<LandingRoutes />);
    expect(
      screen.getByRole('heading', { name: '하루들 랜딩' }),
    ).toBeInTheDocument();

    expect(screen.getByText('잠시 후 다시 시도해주세요')).toBeInTheDocument();
    expect(
      screen.queryByText('게스트 세션 응답 형식이 일치하지 않습니다'),
    ).not.toBeInTheDocument();
    await waitFor(() => {
      expect(console.error).toHaveBeenCalledWith(
        '게스트 체험 진입에 실패했습니다',
        mockGuestEntryRequest.current.status === 'error'
          ? mockGuestEntryRequest.current.error
          : undefined,
      );
    });

    await user.click(screen.getByRole('button', { name: '다시 시도' }));

    expect(mockRetryGuestEntry).toHaveBeenCalledTimes(1);
  });
});
