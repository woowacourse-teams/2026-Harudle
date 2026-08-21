import { beforeEach, describe, expect, it, jest } from '@jest/globals';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import GuestLoginCta from './GuestLoginCta';

const mockTrack = jest.fn();

jest.mock('../../shared/useAnalytics', () => ({
  useAnalytics: () => ({ track: mockTrack }),
}));
jest.mock('../../assets/icons/kakao.svg', () => 'kakao.svg');

beforeEach(() => {
  mockTrack.mockReset();
});

describe('게스트 로그인 CTA', () => {
  it('기존 Kakao OAuth 진입 경로로 연결한다', () => {
    render(
      <GuestLoginCta
        label="카카오로 로그인하기"
        analyticsEvent="landing_trial_login_clicked"
        location="result"
      />,
    );

    expect(
      screen.getByRole('link', { name: '카카오로 로그인하기' }),
    ).toHaveAttribute('href', '/oauth2/authorization/kakao');
  });

  it('랜딩 종류와 위치에 맞는 로그인 클릭 이벤트를 기록한다', async () => {
    const user = userEvent.setup();
    render(
      <GuestLoginCta
        label="카카오로 시작하기"
        analyticsEvent="landing_direct_login_clicked"
        location="hero"
      />,
    );

    await user.click(screen.getByRole('link', { name: '카카오로 시작하기' }));

    expect(mockTrack).toHaveBeenCalledWith('landing_direct_login_clicked', {
      location: 'hero',
    });
  });
});
