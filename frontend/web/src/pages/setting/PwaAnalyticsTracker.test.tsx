import { beforeEach, describe, expect, it, jest } from '@jest/globals';
import { render } from '@testing-library/react';
import PwaAnalyticsTracker from './PwaAnalyticsTracker';

const mockTrack = jest.fn();
const mockIsPwaInstalled = jest.fn();

jest.mock('../../shared/useAnalytics', () => ({
  useAnalytics: () => ({ track: mockTrack }),
}));

jest.mock('./PwaInstallContext', () => ({
  isPwaInstalled: () => mockIsPwaInstalled(),
}));

beforeEach(() => {
  mockTrack.mockReset();
  mockIsPwaInstalled.mockReset();
  localStorage.clear();
});

describe('PWA 분석 추적', () => {
  it('설치된 PWA로 처음 실행하면 이벤트를 한 번 기록한다', () => {
    mockIsPwaInstalled.mockReturnValue(true);

    const { unmount } = render(<PwaAnalyticsTracker />);
    unmount();
    render(<PwaAnalyticsTracker />);

    expect(mockTrack).toHaveBeenCalledTimes(1);
    expect(mockTrack).toHaveBeenCalledWith('pwa_first_launched');
  });

  it('일반 브라우저에서 실행하면 이벤트를 기록하지 않는다', () => {
    mockIsPwaInstalled.mockReturnValue(false);

    render(<PwaAnalyticsTracker />);

    expect(mockTrack).not.toHaveBeenCalled();
  });
});
