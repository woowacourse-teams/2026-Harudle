import { beforeEach, describe, expect, it, jest } from '@jest/globals';
import {
  act,
  fireEvent,
  render,
  screen,
  waitFor,
} from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import type { ReactNode } from 'react';
import { RequestError } from '../../shared/api';
import type {
  acceptTerms,
  getAgreementStatus,
  AgreementStatus,
} from '../../domain/legal/agreements';
import type {
  getLegalDocuments,
  LegalDocumentsResponse,
} from '../../domain/legal/legalDocuments';
import type { logout } from '../../domain/user/logout';
import ConsentPage from './ConsentPage';
import terms from '../../mocks/legal/terms-2026-10-22.json';
import privacy from '../../mocks/legal/privacy-2026-08-24.json';

const mockGetStatus = jest.fn<typeof getAgreementStatus>();
const mockAcceptTerms = jest.fn<typeof acceptTerms>();
const mockGetDocuments = jest.fn<typeof getLegalDocuments>();
const mockLogout = jest.fn<typeof logout>();
const mockRestore = jest.fn<() => Promise<void>>();
const mockCsrf = jest.fn<() => Promise<string>>();
const mockSetToken = jest.fn();
const mockResetUser = jest.fn();
const mockNavigate = jest.fn();
let mockLocationState: unknown;

jest.mock('react-router', () => ({
  useNavigate: () => mockNavigate,
  useLocation: () => ({ state: mockLocationState }),
  Link: ({ to, children, ...props }: { to: string; children: ReactNode }) => (
    <a href={to} {...props}>
      {children}
    </a>
  ),
}));
jest.mock('../../domain/legal/agreements', () => ({
  getAgreementStatus: (
    ...args: Parameters<typeof getAgreementStatus>
  ): Promise<AgreementStatus> => mockGetStatus(...args),
  acceptTerms: (
    ...args: Parameters<typeof acceptTerms>
  ): Promise<AgreementStatus> => mockAcceptTerms(...args),
}));
jest.mock('../../domain/legal/legalDocuments', () => ({
  getLegalDocuments: (
    ...args: Parameters<typeof getLegalDocuments>
  ): Promise<LegalDocumentsResponse> => mockGetDocuments(...args),
}));
jest.mock('../../domain/user/logout', () => ({
  logout: (...args: Parameters<typeof logout>): Promise<void> =>
    mockLogout(...args),
}));
jest.mock('../../shared/auth', () => ({
  ...jest.requireActual<typeof import('../../shared/auth')>(
    '../../shared/auth',
  ),
  restoreAccessToken: (): Promise<void> => mockRestore(),
  requestCsrfToken: (): Promise<string> => mockCsrf(),
  setAccessToken: (token: string | null): void => {
    mockSetToken(token);
  },
}));
jest.mock('../../posthog/useAnalytics', () => ({
  useAnalytics: () => ({ resetUser: mockResetUser }),
}));
jest.mock('../../assets/images/loading-animation.webp', () => 'loading.webp');

const status: AgreementStatus = {
  minimumAge: 14,
  enforcementEnabled: true,
  requiresAcceptance: true,
  terms: { version: terms.version, contentHash: terms.contentHash },
  privacyNotice: { version: privacy.version, contentHash: privacy.contentHash },
  acceptedAt: null,
};
const accepted: AgreementStatus = {
  ...status,
  requiresAcceptance: false,
  acceptedAt: '2026-10-25T08:00:00Z',
};
const catalog: LegalDocumentsResponse = {
  documents: [
    { ...terms, type: 'TERMS' },
    { ...privacy, type: 'PRIVACY' },
  ],
};
beforeEach((): void => {
  mockLocationState = { returnTo: '/setting?tab=account#profile' };
  mockGetStatus.mockReset().mockResolvedValue(status);
  mockAcceptTerms.mockReset().mockResolvedValue(accepted);
  mockGetDocuments.mockReset().mockResolvedValue(catalog);
  mockLogout.mockReset();
  mockRestore.mockReset().mockResolvedValue();
  mockCsrf.mockReset().mockResolvedValue('csrf');
  mockSetToken.mockReset();
  mockResetUser.mockReset();
  mockNavigate.mockReset();
});

const selectRequiredItems = async (): Promise<
  ReturnType<typeof userEvent.setup>
> => {
  const user = userEvent.setup();
  await user.click(
    await screen.findByRole('checkbox', { name: '[필수] 이용약관 동의' }),
  );
  await user.click(
    screen.getByRole('checkbox', { name: '[필수] 만 14세 이상입니다' }),
  );
  return user;
};

describe('명시적 이용약관 동의', (): void => {
  it('약관·연령 자기확인을 각각 선택하고 한 번만 저장한 뒤 원래 화면으로 이동한다', async (): Promise<void> => {
    let resolveAcceptance: ((value: AgreementStatus) => void) | undefined;
    mockAcceptTerms.mockImplementationOnce(
      (): Promise<AgreementStatus> =>
        new Promise((resolve): void => {
          resolveAcceptance = resolve;
        }),
    );
    const user = userEvent.setup();
    render(<ConsentPage />);
    const termsCheckbox = await screen.findByRole('checkbox', {
      name: '[필수] 이용약관 동의',
    });
    expect(termsCheckbox).not.toBeChecked();
    expect(
      screen.getByRole('checkbox', { name: '[필수] 만 14세 이상입니다' }),
    ).not.toBeChecked();
    expect(screen.getAllByRole('checkbox')).toHaveLength(2);
    const submit = screen.getByRole('button', { name: '동의하고 시작하기' });
    expect(submit).toBeDisabled();
    await user.click(termsCheckbox);
    expect(submit).toBeDisabled();
    await user.click(
      screen.getByRole('checkbox', { name: '[필수] 만 14세 이상입니다' }),
    );
    await user.click(submit);
    const form = termsCheckbox.closest('form');
    if (!form) throw new Error('동의 폼이 없어요.');
    fireEvent.submit(form);
    expect(mockAcceptTerms).toHaveBeenCalledTimes(1);
    expect(mockAcceptTerms.mock.calls[0]?.[0]).toEqual({
      termsVersion: terms.version,
      termsContentHash: terms.contentHash,
      privacyNoticeVersion: privacy.version,
      privacyNoticeContentHash: privacy.contentHash,
      accepted: true,
      ageConfirmed: true,
    });
    expect(
      screen.getByRole('button', { name: '로그아웃하고 둘러보기' }),
    ).toBeDisabled();
    expect(
      screen.getByRole('link', { name: '이용약관 전문 보기' }),
    ).toHaveAttribute('aria-disabled', 'true');
    expect(mockNavigate).not.toHaveBeenCalled();
    await act(async (): Promise<void> => {
      resolveAcceptance?.(accepted);
    });
    expect(mockNavigate).toHaveBeenCalledWith('/setting?tab=account#profile', {
      replace: true,
    });
  });
  it('저장 실패는 선택을 유지하고 같은 버튼으로 재시도한다', async (): Promise<void> => {
    mockAcceptTerms.mockRejectedValueOnce(new Error('실패'));
    render(<ConsentPage />);
    const user = await selectRequiredItems();
    await user.click(screen.getByRole('button', { name: '동의하고 시작하기' }));
    expect(await screen.findByRole('alert')).toHaveTextContent(
      '동의를 저장하지 못했어요.',
    );
    expect(
      screen.getByRole('checkbox', { name: '[필수] 이용약관 동의' }),
    ).toBeChecked();
    expect(
      screen.getByRole('checkbox', { name: '[필수] 만 14세 이상입니다' }),
    ).toBeChecked();
    await user.click(screen.getByRole('button', { name: '동의하고 시작하기' }));
    await waitFor(() => expect(mockNavigate).toHaveBeenCalledTimes(1));
    expect(mockAcceptTerms).toHaveBeenCalledTimes(2);
  });
  it('문서가 바뀌면 새 문서를 불러오고 두 필수 항목을 다시 확인한다', async (): Promise<void> => {
    const nextTerms = {
      ...terms,
      type: 'TERMS' as const,
      version: '2026-11-01',
      contentHash: 'f'.repeat(64),
    };
    mockAcceptTerms.mockRejectedValueOnce(
      new RequestError({
        type: 'about:blank',
        title: 'Changed',
        status: 409,
        detail: '문서가 바뀌었어요.',
        code: 'LEGAL_DOCUMENT_CHANGED',
        instance: '/api/v1/me/agreements',
      }),
    );
    render(<ConsentPage />);
    const user = await selectRequiredItems();
    mockGetDocuments.mockResolvedValueOnce({
      documents: [nextTerms, { ...privacy, type: 'PRIVACY' }],
    });
    mockGetStatus.mockResolvedValueOnce({
      ...status,
      terms: { version: nextTerms.version, contentHash: nextTerms.contentHash },
    });
    await user.click(screen.getByRole('button', { name: '동의하고 시작하기' }));
    expect(
      await screen.findByRole('link', { name: '이용약관 전문 보기' }),
    ).toHaveAttribute('href', '/legal/terms/2026-11-01');
    expect(
      screen.getByRole('checkbox', { name: '[필수] 이용약관 동의' }),
    ).not.toBeChecked();
    expect(
      screen.getByRole('checkbox', { name: '[필수] 만 14세 이상입니다' }),
    ).not.toBeChecked();
    expect(
      screen.getByRole('button', { name: '동의하고 시작하기' }),
    ).toBeDisabled();
    expect(screen.getByRole('alert')).toHaveTextContent('문서가 바뀌었어요.');
    expect(mockAcceptTerms).toHaveBeenCalledTimes(1);
    expect(mockNavigate).not.toHaveBeenCalled();
  });
  it('로그아웃 실패는 동의 화면과 인증 상태를 유지하고 다시 시도할 수 있다', async (): Promise<void> => {
    mockLogout.mockRejectedValueOnce(new Error('실패'));
    const user = userEvent.setup();
    render(<ConsentPage />);
    await user.click(
      await screen.findByRole('button', { name: '로그아웃하고 둘러보기' }),
    );
    expect(await screen.findByRole('alert')).toHaveTextContent(
      '로그아웃하지 못했어요.',
    );
    expect(mockLogout).toHaveBeenCalledWith({ csrfToken: 'csrf' });
    expect(mockSetToken).not.toHaveBeenCalled();
    expect(mockResetUser).not.toHaveBeenCalled();
    expect(mockNavigate).not.toHaveBeenCalled();
    expect(
      screen.getByRole('button', { name: '로그아웃하고 둘러보기' }),
    ).toBeEnabled();
  });
  it('제재로 복원이 막혀도 재시도 반복 대신 쿠키 로그아웃을 선택할 수 있다', async (): Promise<void> => {
    mockRestore.mockRejectedValueOnce(
      new RequestError({
        type: 'about:blank',
        title: 'Suspended',
        status: 403,
        detail: 'internal suspension detail',
        code: 'ACCOUNT_SUSPENDED',
        instance: '/api/v1/auth/refresh',
      }),
    );
    mockLogout.mockRejectedValueOnce(new Error('로그아웃 일시 실패'));
    const user = userEvent.setup();
    render(<ConsentPage />);
    expect(await screen.findByRole('alert')).toHaveTextContent(
      '관리자에 의해 이용이 제한된 계정이에요.',
    );
    expect(
      screen.queryByText('internal suspension detail'),
    ).not.toBeInTheDocument();
    expect(
      screen.queryByRole('button', { name: '다시 불러오기' }),
    ).not.toBeInTheDocument();
    await user.click(
      screen.getByRole('button', { name: '로그아웃하고 둘러보기' }),
    );
    expect(mockLogout).toHaveBeenCalledWith({ csrfToken: 'csrf' });
    expect(mockRestore).toHaveBeenCalledTimes(1);
  });

  it('분석 SDK 초기화에 실패해도 서버에서 성공한 로그아웃을 실패로 안내하지 않는다', async (): Promise<void> => {
    mockLogout.mockResolvedValueOnce();
    mockResetUser.mockImplementationOnce((): void => {
      throw new Error('분석 SDK 장애');
    });
    localStorage.setItem('harudle.has-completed-oauth', 'true');
    const consoleError = jest
      .spyOn(console, 'error')
      .mockImplementation((): void => undefined);
    const user = userEvent.setup();
    render(<ConsentPage />);
    await user.click(
      await screen.findByRole('button', { name: '로그아웃하고 둘러보기' }),
    );
    await waitFor(() => expect(mockSetToken).toHaveBeenCalledWith(null));
    expect(localStorage.getItem('harudle.has-completed-oauth')).toBeNull();
    expect(mockResetUser).toHaveBeenCalledTimes(1);
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
    consoleError.mockRestore();
  });

  it('완료 전에 화면을 벗어나면 늦게 온 응답으로 이동하지 않는다', async (): Promise<void> => {
    let resolveAcceptance: ((value: AgreementStatus) => void) | undefined;
    mockAcceptTerms.mockImplementationOnce(
      (): Promise<AgreementStatus> =>
        new Promise((resolve): void => {
          resolveAcceptance = resolve;
        }),
    );
    const { unmount } = render(<ConsentPage />);
    const user = await selectRequiredItems();
    await user.click(screen.getByRole('button', { name: '동의하고 시작하기' }));
    const signal = mockAcceptTerms.mock.calls[0]?.[1]?.signal;
    unmount();
    await act(async (): Promise<void> => {
      resolveAcceptance?.(accepted);
    });
    expect(signal?.aborted).toBe(true);
    expect(mockNavigate).not.toHaveBeenCalled();
  });
  it('라우터 state가 외부 주소여도 이미 동의한 회원은 홈으로만 돌아간다', async (): Promise<void> => {
    mockLocationState = { returnTo: '//evil.example' };
    mockGetStatus.mockResolvedValueOnce(accepted);
    render(<ConsentPage />);
    await waitFor(() =>
      expect(mockNavigate).toHaveBeenCalledWith('/', { replace: true }),
    );
    expect(mockAcceptTerms).not.toHaveBeenCalled();
  });
});
