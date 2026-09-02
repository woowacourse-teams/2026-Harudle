import { beforeEach, describe, expect, it, jest } from '@jest/globals';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import PwaInstallButton from './PwaInstallButton';

const mockTrack = jest.fn();
const mockInstall = jest.fn<() => Promise<void>>();
let mockStatus: 'installable' | 'ios-guide' = 'installable';

jest.mock('../../shared/useAnalytics', () => ({
  useAnalytics: () => ({ track: mockTrack }),
}));

jest.mock('./PwaInstallContext', () => ({
  usePwaInstall: () => ({ status: mockStatus, install: mockInstall }),
}));

jest.mock('../../assets/icons/download.svg', () => 'download.svg');

beforeEach(() => {
  mockTrack.mockReset();
  mockInstall.mockReset();
  mockInstall.mockResolvedValue();
  mockStatus = 'installable';
});

describe('PWA 설치 버튼', () => {
  it('기본 설치창을 여는 클릭 이벤트를 기록한다', async () => {
    const user = userEvent.setup();
    render(<PwaInstallButton />);

    await user.click(
      screen.getByRole('button', { name: /하루들을 앱으로 설치해 보세요/ }),
    );

    expect(mockTrack).toHaveBeenCalledWith('pwa_install_clicked', {
      method: 'native_prompt',
    });
    expect(mockInstall).toHaveBeenCalledTimes(1);
  });

  it('iOS 설치 안내를 여는 클릭 이벤트를 기록한다', async () => {
    const user = userEvent.setup();
    const openSpy = jest.spyOn(window, 'open').mockImplementation(() => null);
    mockStatus = 'ios-guide';
    render(<PwaInstallButton />);

    await user.click(
      screen.getByRole('button', {
        name: /하루들을 홈 화면에 추가해 보세요/,
      }),
    );

    expect(mockTrack).toHaveBeenCalledWith('pwa_install_clicked', {
      method: 'ios_guide',
    });
    expect(openSpy).toHaveBeenCalledTimes(1);
    openSpy.mockRestore();
  });
});
