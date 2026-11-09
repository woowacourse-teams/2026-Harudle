import { beforeEach, describe, expect, it, jest } from '@jest/globals';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import LandingLoginCta from './LandingLoginCta';

const mockTrack = jest.fn();

jest.mock('../../posthog/useAnalytics', () => ({
  useAnalytics: () => ({ track: mockTrack }),
}));

beforeEach(() => {
  mockTrack.mockReset();
});

describe('게스트 로그인 CTA', () => {
  it('로그인 방법 선택 페이지로 연결한다', () => {
    render(
      <LandingLoginCta
        label="로그인하기"
        analyticsEvent="landing_trial_login_clicked"
        location="result"
      />,
    );

    expect(screen.getByRole('link', { name: '로그인하기' })).toHaveAttribute(
      'href',
      '/login',
    );
  });

  it('랜딩 종류와 위치에 맞는 로그인 클릭 이벤트를 기록한다', async () => {
    const user = userEvent.setup();
    render(
      <LandingLoginCta
        label="로그인하기"
        analyticsEvent="landing_direct_login_clicked"
        location="hero"
      />,
    );

    await user.click(screen.getByRole('link', { name: '로그인하기' }));

    expect(mockTrack).toHaveBeenCalledWith('landing_direct_login_clicked', {
      location: 'hero',
    });
  });
});
