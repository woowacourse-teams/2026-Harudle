import { beforeEach, describe, expect, it, jest } from '@jest/globals';
import { act, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { useState, type ReactNode } from 'react';
import type {
  AgreementStatus,
  getAgreementStatus,
} from './domain/legal/agreements';
import AgreementRoute from './AgreementRoute';
import { RequestError } from './shared/api';
import { TERMS_ACCEPTANCE_REQUIRED_EVENT } from './shared/auth';
import terms from './mocks/legal/terms-2026-10-22.json';
import privacy from './mocks/legal/privacy-2026-08-24.json';

const mockGetStatus = jest.fn<typeof getAgreementStatus>();
let mockLocation = {
  pathname: '/setting',
  search: '?tab=account',
  hash: '#profile',
};
const mockRedirect = jest.fn();
const MockMemberScreen = () => {
  const [selection, setSelection] = useState(0);
  return (
    <>
      <p>회원 화면</p>
      <button
        type="button"
        onClick={(): void => setSelection((current): number => current + 1)}
      >
        목록 선택 {selection}
      </button>
    </>
  );
};
jest.mock('react-router', () => ({
  useLocation: () => mockLocation,
  Outlet: () => <MockMemberScreen />,
  Navigate: (props: unknown) => {
    mockRedirect(props);
    return <p>동의 화면으로 이동</p>;
  },
  Link: ({ to, children }: { to: string; children: ReactNode }) => (
    <a href={to}>{children}</a>
  ),
}));
jest.mock('./domain/legal/agreements', () => ({
  getAgreementStatus: (
    ...args: Parameters<typeof getAgreementStatus>
  ): Promise<AgreementStatus> => mockGetStatus(...args),
}));
jest.mock('./shared/DiaryGenerationNotice', () => () => (
  <p>비공개 생성 상태 조회</p>
));
jest.mock('./shared/LoadingSpinner', () => () => <p>확인 중</p>);
const allowed: AgreementStatus = {
  minimumAge: 14,
  enforcementEnabled: true,
  requiresAcceptance: false,
  terms: { version: terms.version, contentHash: terms.contentHash },
  privacyNotice: { version: privacy.version, contentHash: privacy.contentHash },
  acceptedAt: '2026-10-25T08:00:00Z',
};
beforeEach((): void => {
  localStorage.clear();
  localStorage.setItem('harudle.has-completed-oauth', 'true');
  mockGetStatus.mockReset().mockResolvedValue(allowed);
  mockRedirect.mockReset();
  mockLocation = {
    pathname: '/setting',
    search: '?tab=account',
    hash: '#profile',
  };
});

describe('회원 약관 동의 가드', (): void => {
  it('서버 확인 전에는 회원 화면과 비공개 생성 상태 조회를 시작하지 않는다', async (): Promise<void> => {
    let resolveStatus: ((value: AgreementStatus) => void) | undefined;
    mockGetStatus.mockImplementationOnce(
      (): Promise<AgreementStatus> =>
        new Promise((resolve): void => {
          resolveStatus = resolve;
        }),
    );
    render(<AgreementRoute />);
    expect(screen.getByText('확인 중')).toBeInTheDocument();
    expect(screen.queryByText('회원 화면')).not.toBeInTheDocument();
    expect(screen.queryByText('비공개 생성 상태 조회')).not.toBeInTheDocument();
    await act(async (): Promise<void> => {
      resolveStatus?.(allowed);
    });
    expect(screen.getByText('회원 화면')).toBeInTheDocument();
    expect(screen.getByText('비공개 생성 상태 조회')).toBeInTheDocument();
  });
  it('미동의면 원래 내부 경로를 전달하고 회원 콘텐츠는 렌더링하지 않는다', async (): Promise<void> => {
    mockGetStatus.mockResolvedValueOnce({
      ...allowed,
      requiresAcceptance: true,
      acceptedAt: null,
    });
    render(<AgreementRoute />);
    expect(await screen.findByText('동의 화면으로 이동')).toBeInTheDocument();
    expect(mockRedirect).toHaveBeenCalledWith({
      to: '/consent',
      replace: true,
      state: { returnTo: '/setting?tab=account#profile' },
    });
    expect(screen.queryByText('회원 화면')).not.toBeInTheDocument();
    expect(screen.queryByText('비공개 생성 상태 조회')).not.toBeInTheDocument();
  });
  it('비로그인은 동의 상태 API 없이 공개 화면을 허용하고 비공개 조회는 마운트하지 않는다', async (): Promise<void> => {
    localStorage.clear();
    render(<AgreementRoute />);
    expect(await screen.findByText('회원 화면')).toBeInTheDocument();
    expect(mockGetStatus).not.toHaveBeenCalled();
    expect(screen.queryByText('비공개 생성 상태 조회')).not.toBeInTheDocument();
  });
  it('강제 적용이 꺼진 기존 회원은 미동의 상태여도 이용할 수 있다', async (): Promise<void> => {
    mockGetStatus.mockResolvedValueOnce({
      ...allowed,
      enforcementEnabled: false,
      acceptedAt: null,
    });
    render(<AgreementRoute />);
    expect(await screen.findByText('회원 화면')).toBeInTheDocument();
    expect(mockRedirect).not.toHaveBeenCalled();
  });
  it('상태 조회에 실패하면 화면을 막고 재시도한다', async (): Promise<void> => {
    mockGetStatus.mockRejectedValueOnce(new Error('장애'));
    const user = userEvent.setup();
    render(<AgreementRoute />);
    expect(await screen.findByRole('alert')).toBeInTheDocument();
    expect(screen.queryByText('회원 화면')).not.toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: '다시 불러오기' }));
    expect(await screen.findByText('회원 화면')).toBeInTheDocument();
    expect(mockGetStatus.mock.calls[0]?.[0]?.signal?.aborted).toBe(true);
  });
  it('제재 응답은 원문 상세를 노출하지 않고 알려진 안내와 계정 경로를 제공한다', async (): Promise<void> => {
    mockGetStatus.mockRejectedValueOnce(
      new RequestError({
        type: 'about:blank',
        title: 'Suspended',
        status: 403,
        detail: 'internal suspension detail',
        code: 'ACCOUNT_SUSPENDED',
        instance: '/api/v1/me/agreements',
      }),
    );
    render(<AgreementRoute />);
    expect(await screen.findByRole('alert')).toHaveTextContent(
      '관리자에 의해 이용이 제한된 계정이에요.',
    );
    expect(
      screen.queryByText('internal suspension detail'),
    ).not.toBeInTheDocument();
    expect(
      screen.queryByRole('button', { name: '다시 불러오기' }),
    ).not.toBeInTheDocument();
    expect(
      screen.getByRole('link', { name: '계정 이용 안내' }),
    ).toHaveAttribute('href', '/consent');
  });

  it('이전 경로의 늦은 허용 응답은 새 경로의 동의 검사를 건너뛰지 못한다', async (): Promise<void> => {
    const resolutions: Array<(value: AgreementStatus) => void> = [];
    mockGetStatus.mockImplementation(
      (): Promise<AgreementStatus> =>
        new Promise((resolve): void => {
          resolutions.push(resolve);
        }),
    );
    const { rerender } = render(<AgreementRoute />);
    await waitFor(() => expect(resolutions).toHaveLength(1));
    mockLocation = { pathname: '/album', search: '', hash: '' };
    rerender(<AgreementRoute />);
    await waitFor(() => expect(resolutions).toHaveLength(2));
    await act(async (): Promise<void> => {
      resolutions[0]?.(allowed);
    });
    expect(screen.queryByText('회원 화면')).not.toBeInTheDocument();
    expect(mockGetStatus.mock.calls[0]?.[0]?.signal?.aborted).toBe(true);
    await act(async (): Promise<void> => {
      resolutions[1]?.({
        ...allowed,
        requiresAcceptance: true,
        acceptedAt: null,
      });
    });
    expect(screen.getByText('동의 화면으로 이동')).toBeInTheDocument();
  });

  it('검색·해시 변경은 회원 화면의 상태와 진행 중 조회를 유지한다', async (): Promise<void> => {
    const user = userEvent.setup();
    const { rerender } = render(<AgreementRoute />);
    await user.click(
      await screen.findByRole('button', { name: '목록 선택 0' }),
    );
    mockLocation = {
      pathname: '/setting',
      search: '?tab=privacy',
      hash: '#terms',
    };
    rerender(<AgreementRoute />);
    expect(
      screen.getByRole('button', { name: '목록 선택 1' }),
    ).toBeInTheDocument();
    expect(mockGetStatus).toHaveBeenCalledTimes(1);
    expect(mockGetStatus.mock.calls[0]?.[0]?.signal?.aborted).toBe(false);
  });

  it('약관 거절 이벤트는 화면을 닫고 한 번 재검사한 뒤 최신 전체 경로를 동의 화면에 전달한다', async (): Promise<void> => {
    const { rerender } = render(<AgreementRoute />);
    await screen.findByText('회원 화면');
    mockLocation = {
      pathname: '/setting',
      search: '?tab=privacy',
      hash: '#terms',
    };
    rerender(<AgreementRoute />);
    let resolveStatus: ((value: AgreementStatus) => void) | undefined;
    mockGetStatus.mockImplementationOnce(
      (): Promise<AgreementStatus> =>
        new Promise((resolve): void => {
          resolveStatus = resolve;
        }),
    );
    act((): void => {
      window.dispatchEvent(new Event(TERMS_ACCEPTANCE_REQUIRED_EVENT));
    });
    expect(screen.queryByText('회원 화면')).not.toBeInTheDocument();
    expect(screen.queryByText('비공개 생성 상태 조회')).not.toBeInTheDocument();
    await waitFor(() => expect(mockGetStatus).toHaveBeenCalledTimes(2));
    act((): void => {
      window.dispatchEvent(new Event(TERMS_ACCEPTANCE_REQUIRED_EVENT));
    });
    act((): void => {
      window.dispatchEvent(new Event(TERMS_ACCEPTANCE_REQUIRED_EVENT));
    });
    expect(mockGetStatus).toHaveBeenCalledTimes(2);
    await act(async (): Promise<void> => {
      resolveStatus?.({
        ...allowed,
        requiresAcceptance: true,
        acceptedAt: null,
      });
    });
    expect(mockRedirect).toHaveBeenLastCalledWith({
      to: '/consent',
      replace: true,
      state: { returnTo: '/setting?tab=privacy#terms' },
    });
    act((): void => {
      window.dispatchEvent(new Event(TERMS_ACCEPTANCE_REQUIRED_EVENT));
    });
    expect(mockGetStatus).toHaveBeenCalledTimes(2);
  });

  it('초기 검사·조회 오류 중의 이벤트는 재조회 반복을 만들지 않고 직접 재시도할 수 있다', async (): Promise<void> => {
    let rejectStatus: ((error: Error) => void) | undefined;
    mockGetStatus.mockImplementationOnce(
      (): Promise<AgreementStatus> =>
        new Promise((_resolve, reject): void => {
          rejectStatus = reject;
        }),
    );
    const user = userEvent.setup();
    render(<AgreementRoute />);
    act((): void => {
      window.dispatchEvent(new Event(TERMS_ACCEPTANCE_REQUIRED_EVENT));
    });
    expect(mockGetStatus).toHaveBeenCalledTimes(1);
    await act(async (): Promise<void> => {
      rejectStatus?.(new Error('일시적인 조회 실패'));
    });
    await screen.findByRole('alert');
    act((): void => {
      window.dispatchEvent(new Event(TERMS_ACCEPTANCE_REQUIRED_EVENT));
    });
    expect(mockGetStatus).toHaveBeenCalledTimes(1);
    await user.click(screen.getByRole('button', { name: '다시 불러오기' }));
    expect(await screen.findByText('회원 화면')).toBeInTheDocument();
    expect(mockGetStatus).toHaveBeenCalledTimes(2);
  });
});
