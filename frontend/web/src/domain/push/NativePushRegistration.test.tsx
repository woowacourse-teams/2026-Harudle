import { StrictMode, useState } from 'react';
import {
  afterEach,
  beforeEach,
  describe,
  expect,
  it,
  jest,
} from '@jest/globals';
import {
  act,
  cleanup,
  fireEvent,
  render,
  screen,
  waitFor,
} from '@testing-library/react';
import NotificationToggle from '../../pages/setting/NotificationToggle';
import NativePushRegistration, {
  type NativePushControls,
} from './NativePushRegistration';
import {
  clearNativePushRegistration,
  getPushPreference,
  setPushPreference,
  updatePushRegistration,
  type PushState,
} from './pushRegistration';
import { PUSH_COPY } from './copy';

const mockProfile = jest.fn<() => Promise<{ id: string }>>();
const mockPostMessage = jest.fn<(message: string) => void>();
const mockUpdate = jest.fn<typeof updatePushRegistration>();
jest.mock('../user/profile', () => ({ getUserProfile: () => mockProfile() }));
jest.mock('./pushRegistration', () => ({
  ...jest.requireActual<typeof import('./pushRegistration')>(
    './pushRegistration',
  ),
  updatePushRegistration: (
    ...args: Parameters<typeof updatePushRegistration>
  ) => mockUpdate(...args),
}));
const PushSettings = () => {
  const [push, setPush] = useState<NativePushControls | null>(null);
  return (
    <>
      <NativePushRegistration onChange={setPush} />
      <NotificationToggle push={push} />
    </>
  );
};

const firstState: PushState = {
  registration: { id: 'registration-1', version: 0 },
  stateVersion: 1,
};
const emit = (payload: unknown): void => {
  window.dispatchEvent(
    new CustomEvent('harudle:message', {
      detail: { type: 'push.registrationResult', payload },
    }),
  );
};

beforeEach((): void => {
  localStorage.clear();
  localStorage.setItem('harudle.has-completed-oauth', 'true');
  mockProfile.mockReset().mockResolvedValue({ id: 'alice' });
  mockUpdate.mockReset().mockResolvedValue(firstState);
  mockPostMessage.mockReset();
  Object.defineProperties(window, {
    isHarudleApp: { configurable: true, value: true },
    ReactNativeWebView: {
      configurable: true,
      value: { postMessage: mockPostMessage },
    },
  });
  jest.spyOn(window, 'confirm').mockReturnValue(true);
  jest.spyOn(window, 'alert').mockImplementation((): void => undefined);
  jest.spyOn(console, 'warn').mockImplementation((): void => undefined);
});
afterEach((): void => {
  cleanup();
  jest.restoreAllMocks();
  Reflect.deleteProperty(window, 'isHarudleApp');
  Reflect.deleteProperty(window, 'ReactNativeWebView');
});

describe('네이티브 푸시 연결', () => {
  it('저장된 동의가 있어도 실제 등록이 실패하면 켜짐으로 표시하지 않는다', async (): Promise<void> => {
    setPushPreference('alice', true);
    render(<PushSettings />);
    await waitFor((): void => expect(mockPostMessage).toHaveBeenCalledTimes(1));
    const toggle = screen.getByRole('switch');
    expect(toggle).not.toBeChecked();
    expect(toggle).toBeDisabled();
    emit({ userId: 'alice', status: 'error' });
    await waitFor((): void => expect(toggle).toBeEnabled());
    expect(toggle).not.toBeChecked();
    expect(mockUpdate).not.toHaveBeenCalled();
  });

  it('일반 브라우저에서는 설정 토글과 푸시 등록을 제공하지 않는다', (): void => {
    Object.defineProperty(window, 'isHarudleApp', {
      configurable: true,
      value: false,
    });
    render(<PushSettings />);
    expect(screen.queryByRole('switch')).not.toBeInTheDocument();
    expect(mockProfile).not.toHaveBeenCalled();
  });

  it('처음 거절한 뒤 설정에서 켜고 끄며 같은 FID로 다시 켤 수 있다', async (): Promise<void> => {
    jest.mocked(window.confirm).mockReturnValue(false);
    render(<PushSettings />);
    const toggle = await screen.findByRole('switch', { name: '푸시 알림' });
    expect(toggle).not.toBeChecked();
    fireEvent.click(toggle);
    expect(toggle).toBeDisabled();
    expect(JSON.parse(mockPostMessage.mock.calls[0][0])).toEqual({
      type: 'push.register',
      payload: { userId: 'alice' },
    });
    emit({ userId: 'alice', status: 'registered', fid: 'fid-1' });
    await waitFor((): void => expect(toggle).toBeChecked());

    let finish: (state: PushState) => void = (): void => undefined;
    const disabledState: PushState = { registration: null, stateVersion: 2 };
    mockUpdate.mockReturnValueOnce(
      new Promise<PushState>((resolve): void => {
        finish = resolve;
      }),
    );
    fireEvent.click(toggle);
    expect(toggle).toBeDisabled();
    await waitFor((): void => expect(mockUpdate).toHaveBeenCalledTimes(2));
    expect(mockUpdate.mock.calls[1].slice(1, 3)).toEqual([null, firstState]);
    emit({ userId: 'alice', status: 'registered', fid: 'late-fid' });
    await act(async (): Promise<void> => finish(disabledState));
    expect(toggle).not.toBeChecked();
    expect(toggle).toBeEnabled();
    expect(
      JSON.parse(mockPostMessage.mock.calls.at(-1)?.[0] ?? 'null'),
    ).toEqual({ type: 'push.unregister' });

    fireEvent.click(toggle);
    emit({ userId: 'alice', status: 'registered', fid: 'fid-1' });
    await waitFor((): void => expect(toggle).toBeChecked());
    expect(mockUpdate).toHaveBeenCalledTimes(3);
    expect(mockUpdate.mock.calls[2].slice(1, 3)).toEqual([
      'fid-1',
      disabledState,
    ]);
  });

  it('끄기 요청이 실패하면 켜진 상태를 유지하고 다시 시도할 수 있다', async (): Promise<void> => {
    setPushPreference('alice', true);
    render(<PushSettings />);
    await waitFor((): void => expect(mockPostMessage).toHaveBeenCalledTimes(1));
    emit({ userId: 'alice', status: 'registered', fid: 'fid-1' });
    const toggle = await screen.findByRole('switch');
    await waitFor((): void => expect(toggle).toBeEnabled());
    mockUpdate.mockRejectedValueOnce(new Error('offline'));
    fireEvent.click(toggle);
    await waitFor((): void =>
      expect(window.alert).toHaveBeenCalledWith(PUSH_COPY.failed),
    );
    expect(toggle).toBeChecked();
    expect(toggle).toBeEnabled();
    expect(getPushPreference('alice')).toBe(true);
    fireEvent.click(toggle);
    await waitFor((): void => expect(toggle).not.toBeChecked());
    expect(getPushPreference('alice')).toBe(false);
  });

  it('기기에서 권한을 거절하면 토글을 끄고 기기 설정을 안내한다', async (): Promise<void> => {
    jest.mocked(window.confirm).mockReturnValue(false);
    render(<PushSettings />);
    const toggle = await screen.findByRole('switch');
    fireEvent.click(toggle);
    emit({ userId: 'alice', status: 'denied' });
    await waitFor((): void => expect(toggle).toBeEnabled());
    expect(toggle).not.toBeChecked();
    expect(getPushPreference('alice')).toBe(false);
    expect(window.alert).toHaveBeenCalledWith(PUSH_COPY.permissionDenied);
  });

  it('StrictMode에서도 동의를 한 번만 묻고 네이티브 FID를 받은 뒤 서버에 등록한다', async (): Promise<void> => {
    render(
      <StrictMode>
        <NativePushRegistration />
      </StrictMode>,
    );
    await waitFor((): void => expect(mockPostMessage).toHaveBeenCalledTimes(1));
    expect(window.confirm).toHaveBeenCalledTimes(1);
    expect(JSON.parse(mockPostMessage.mock.calls[0][0])).toEqual({
      type: 'push.register',
      payload: { userId: 'alice' },
    });
    expect(mockUpdate).not.toHaveBeenCalled();
    emit({ userId: 'alice', status: 'registered', fid: 'fid-1' });
    await waitFor((): void => expect(mockUpdate).toHaveBeenCalledTimes(1));
    expect(mockUpdate.mock.calls[0].slice(1, 3)).toEqual([
      'fid-1',
      { registration: null, stateVersion: 0 },
    ]);
    emit({ userId: 'alice', status: 'registered', fid: 'fid-1' });
    await act(async (): Promise<void> => {
      await Promise.resolve();
    });
    expect(mockUpdate).toHaveBeenCalledTimes(1);
  });

  it('사용자가 수신을 선택하지 않으면 권한 요청과 서버 등록을 시작하지 않는다', async (): Promise<void> => {
    jest.mocked(window.confirm).mockReturnValue(false);
    render(<NativePushRegistration />);
    await waitFor((): void => expect(window.confirm).toHaveBeenCalledTimes(1));
    expect(getPushPreference('alice')).toBe(false);
    expect(mockPostMessage).not.toHaveBeenCalled();
    expect(mockUpdate).not.toHaveBeenCalled();
  });

  it('이전에 수신한 계정은 권한을 다시 묻지 않고 동기화하며 권한 거절 시 서버 등록을 해제한다', async (): Promise<void> => {
    setPushPreference('alice', true);
    render(<NativePushRegistration />);
    await waitFor((): void => expect(mockPostMessage).toHaveBeenCalledTimes(1));
    expect(JSON.parse(mockPostMessage.mock.calls[0][0])).toEqual({
      type: 'push.sync',
      payload: { userId: 'alice' },
    });
    expect(window.confirm).not.toHaveBeenCalled();
    emit({ userId: 'alice', status: 'denied' });
    await waitFor((): void => expect(mockUpdate).toHaveBeenCalledTimes(1));
    expect(mockUpdate.mock.calls[0][1]).toBeNull();
    expect(getPushPreference('alice')).toBe(false);
  });

  it('등록 중 FID가 바뀌면 앞 요청의 응답 번호를 사용해 순서대로 갱신한다', async (): Promise<void> => {
    let finish: (state: PushState) => void = (): void => undefined;
    mockUpdate.mockReturnValueOnce(
      new Promise<PushState>((resolve): void => {
        finish = resolve;
      }),
    );
    render(<NativePushRegistration />);
    await waitFor((): void => expect(mockPostMessage).toHaveBeenCalledTimes(1));
    emit({ userId: 'alice', status: 'registered', fid: 'fid-1' });
    await waitFor((): void => expect(mockUpdate).toHaveBeenCalledTimes(1));
    emit({ userId: 'alice', status: 'registered', fid: 'fid-2' });
    expect(mockUpdate).toHaveBeenCalledTimes(1);
    await act(async (): Promise<void> => finish(firstState));
    await waitFor((): void => expect(mockUpdate).toHaveBeenCalledTimes(2));
    expect(mockUpdate.mock.calls[1].slice(1, 3)).toEqual(['fid-2', firstState]);
  });

  it('다른 계정의 응답과 로그아웃 후 늦게 도착한 FID는 등록하지 않는다', async (): Promise<void> => {
    render(<NativePushRegistration />);
    await waitFor((): void => expect(mockPostMessage).toHaveBeenCalledTimes(1));
    emit({ userId: 'bob', status: 'registered', fid: 'bob-fid' });
    clearNativePushRegistration();
    emit({ userId: 'alice', status: 'registered', fid: 'late-fid' });
    await act(async (): Promise<void> => {
      await Promise.resolve();
    });
    expect(mockUpdate).not.toHaveBeenCalled();
    expect(getPushPreference('alice')).toBeNull();
    expect(JSON.parse(mockPostMessage.mock.calls[1][0])).toEqual({
      type: 'push.unregister',
    });
  });

  it('다른 계정의 수신 선택은 새 계정에 적용하지 않는다', async (): Promise<void> => {
    setPushPreference('bob', true);
    render(<NativePushRegistration />);
    await waitFor((): void => expect(window.confirm).toHaveBeenCalledTimes(1));
    expect(getPushPreference('alice')).toBe(true);
  });

  it('서버 등록 실패는 안내하고 설정에서 다시 켤 수 있다', async (): Promise<void> => {
    mockUpdate.mockRejectedValueOnce(new Error('offline'));
    render(<PushSettings />);
    await waitFor((): void => expect(mockPostMessage).toHaveBeenCalledTimes(1));
    emit({ userId: 'alice', status: 'registered', fid: 'fid-1' });
    await waitFor((): void =>
      expect(window.alert).toHaveBeenCalledWith(PUSH_COPY.failed),
    );
    fireEvent.click(screen.getByRole('switch'));
    emit({ userId: 'alice', status: 'registered', fid: 'fid-1' });
    await waitFor((): void => expect(mockUpdate).toHaveBeenCalledTimes(2));
  });
});
