import { beforeEach, describe, expect, it, jest } from '@jest/globals';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import CampusConnectPage from './CampusConnectPage';
import { CAMPUS_CONNECT_COPY } from './copy';
import { issueCampusLinkCode, openCampus } from './campusLinkApi';
import { RequestError } from '../../shared/api';

const STATE = 's'.repeat(43);
const mockNavigate = jest.fn();
const mockSearchParams = new URLSearchParams();

jest.mock('react-router', () => ({
  useNavigate: () => mockNavigate,
  useSearchParams: () => [mockSearchParams],
}));
jest.mock('./campusLinkApi', () => ({
  ...jest.requireActual<typeof import('./campusLinkApi')>('./campusLinkApi'),
  issueCampusLinkCode: jest.fn(),
  openCampus: jest.fn(),
}));

beforeEach((): void => {
  jest.clearAllMocks();
  localStorage.clear();
  sessionStorage.clear();
  for (const key of [...mockSearchParams.keys()]) mockSearchParams.delete(key);
  mockSearchParams.set('state', STATE);
});

describe('우테코 캠퍼스 연결 화면', () => {
  it('로그인하지 않았으면 돌아올 경로를 남기고 로그인으로 보낸다', async (): Promise<void> => {
    render(<CampusConnectPage />);

    await waitFor((): void => {
      expect(mockNavigate).toHaveBeenCalledWith('/login', { replace: true });
    });
    expect(sessionStorage.getItem('harudle.post-login-path')).toBe(
      `/campus/connect?state=${STATE}`,
    );
  });

  it('연동하기를 누르면 코드를 받아 캠퍼스 주소로 이동한다', async (): Promise<void> => {
    localStorage.setItem('harudle.has-completed-oauth', 'true');
    jest.mocked(issueCampusLinkCode).mockResolvedValue({
      code: 'c'.repeat(43),
      redirectUri: 'https://campus.harudle.com/auth/harudle/callback',
      expiresAt: '2026-11-09T10:01:00Z',
    });
    render(<CampusConnectPage />);

    await userEvent.click(
      screen.getByRole('button', { name: CAMPUS_CONNECT_COPY.confirm }),
    );

    await waitFor((): void => {
      expect(openCampus).toHaveBeenCalledWith(
        `https://campus.harudle.com/auth/harudle/callback?code=${'c'.repeat(43)}&state=${STATE}`,
      );
    });
    expect(issueCampusLinkCode).toHaveBeenCalledWith(STATE);
  });

  it('닉네임이 없으면 먼저 정하라고 안내한다', async (): Promise<void> => {
    localStorage.setItem('harudle.has-completed-oauth', 'true');
    jest.mocked(issueCampusLinkCode).mockRejectedValue(
      new RequestError({
        type: 'about:blank',
        title: 'Public profile not assigned',
        status: 409,
        detail: '공개 프로필을 먼저 배정해야 합니다.',
        instance: '/api/v1/campus/link-codes',
        code: 'PUBLIC_PROFILE_NOT_ASSIGNED',
      }),
    );
    render(<CampusConnectPage />);

    await userEvent.click(
      screen.getByRole('button', { name: CAMPUS_CONNECT_COPY.confirm }),
    );

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
    expect(mockNavigate).not.toHaveBeenCalled();
  });
});
