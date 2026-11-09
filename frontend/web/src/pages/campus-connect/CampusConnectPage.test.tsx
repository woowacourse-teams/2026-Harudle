import { beforeEach, describe, expect, it, jest } from '@jest/globals';
import { act, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import CampusConnectPage from './CampusConnectPage';
import { CAMPUS_CONNECT_COPY } from './copy';
import {
  issueCampusLinkToken,
  openCampus,
  openInNewTab,
} from './campusLinkApi';
import { RequestError, type ProblemDetails } from '../../shared/api';

const STATE = 's'.repeat(43);
const mockNavigate = jest.fn();
const mockSearchParams = new URLSearchParams();

jest.mock('react-router', () => ({
  useNavigate: () => mockNavigate,
  useSearchParams: () => [mockSearchParams],
}));
jest.mock('./campusLinkApi', () => ({
  ...jest.requireActual<typeof import('./campusLinkApi')>('./campusLinkApi'),
  issueCampusLinkToken: jest.fn(),
  openCampus: jest.fn(),
  openInNewTab: jest.fn(),
}));

const problem = (code: string, status: ProblemDetails['status']) =>
  new RequestError({
    type: 'about:blank',
    title: code,
    status,
    detail: code,
    instance: '/api/v1/campus/link-tokens',
    code,
  });

const clickConfirm = async (): Promise<void> => {
  await userEvent.click(
    screen.getByRole('button', { name: CAMPUS_CONNECT_COPY.confirm }),
  );
};

beforeEach((): void => {
  jest.clearAllMocks();
  localStorage.clear();
  for (const key of [...mockSearchParams.keys()]) mockSearchParams.delete(key);
  mockSearchParams.set('state', STATE);
  jest.mocked(openInNewTab).mockReturnValue(true);
});

describe('우테코 캠퍼스 연결 화면', () => {
  it('로그인 전이면 새 탭으로 로그인을 열고, 로그인을 마치면 연동 화면으로 바뀐다', async (): Promise<void> => {
    render(<CampusConnectPage />);

    await userEvent.click(
      screen.getByRole('button', { name: CAMPUS_CONNECT_COPY.login }),
    );
    expect(openInNewTab).toHaveBeenCalledWith('/login');

    localStorage.setItem('harudle.has-completed-oauth', 'true');
    act((): void => {
      window.dispatchEvent(new Event('focus'));
    });

    expect(
      await screen.findByRole('button', { name: CAMPUS_CONNECT_COPY.confirm }),
    ).toBeInTheDocument();
    expect(mockNavigate).not.toHaveBeenCalled();
  });

  it('새 탭이 막히면 팝업 허용을 안내한다', async (): Promise<void> => {
    jest.mocked(openInNewTab).mockReturnValue(false);
    render(<CampusConnectPage />);

    await userEvent.click(
      screen.getByRole('button', { name: CAMPUS_CONNECT_COPY.login }),
    );

    expect(screen.getByRole('alert')).toHaveTextContent(
      CAMPUS_CONNECT_COPY.popupBlocked,
    );
  });

  it('연동하기를 누르면 서명 토큰을 받아 캠퍼스 주소로 이동한다', async (): Promise<void> => {
    localStorage.setItem('harudle.has-completed-oauth', 'true');
    jest.mocked(issueCampusLinkToken).mockResolvedValue({
      token: 'header.payload.signature',
      redirectUri: 'https://campus.harudle.com/auth/harudle/callback',
      expiresAt: '2026-11-09T10:01:00Z',
    });
    render(<CampusConnectPage />);

    await clickConfirm();

    await waitFor((): void => {
      expect(openCampus).toHaveBeenCalledWith(
        `https://campus.harudle.com/auth/harudle/callback?token=header.payload.signature&state=${STATE}`,
      );
    });
    expect(issueCampusLinkToken).toHaveBeenCalledWith(STATE);
  });

  it('약관 동의가 필요하면 약관을 새 탭으로 열 수 있게 한다', async (): Promise<void> => {
    localStorage.setItem('harudle.has-completed-oauth', 'true');
    jest
      .mocked(issueCampusLinkToken)
      .mockRejectedValue(problem('TERMS_ACCEPTANCE_REQUIRED', 403));
    render(<CampusConnectPage />);

    await clickConfirm();

    expect(await screen.findByRole('alert')).toHaveTextContent(
      CAMPUS_CONNECT_COPY.termsRequired,
    );
    await userEvent.click(
      screen.getByRole('button', { name: CAMPUS_CONNECT_COPY.openTerms }),
    );
    expect(openInNewTab).toHaveBeenCalledWith('/consent');
    expect(
      screen.getByRole('button', { name: CAMPUS_CONNECT_COPY.confirm }),
    ).toBeInTheDocument();
  });

  it('닉네임이 없으면 먼저 정하라고 안내한다', async (): Promise<void> => {
    localStorage.setItem('harudle.has-completed-oauth', 'true');
    jest
      .mocked(issueCampusLinkToken)
      .mockRejectedValue(problem('PUBLIC_PROFILE_NOT_ASSIGNED', 409));
    render(<CampusConnectPage />);

    await clickConfirm();

    expect(await screen.findByRole('alert')).toHaveTextContent(
      CAMPUS_CONNECT_COPY.profileRequired,
    );
    expect(openCampus).not.toHaveBeenCalled();
  });

  it('state가 올바르지 않으면 연동하지 않는다', (): void => {
    mockSearchParams.set('state', 'short');
    render(<CampusConnectPage />);

    expect(screen.getByRole('alert')).toHaveTextContent(
      CAMPUS_CONNECT_COPY.invalidLink,
    );
  });
});
