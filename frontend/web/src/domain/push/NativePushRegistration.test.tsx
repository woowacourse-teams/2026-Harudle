import { StrictMode } from 'react';
import {
  afterEach,
  beforeEach,
  describe,
  expect,
  it,
  jest,
} from '@jest/globals';
import { act, cleanup, render, waitFor } from '@testing-library/react';
import NativePushRegistration from './NativePushRegistration';
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

  it('서버 등록 실패는 안내하고 다음 FID 통지에서 다시 시도할 수 있다', async (): Promise<void> => {
    mockUpdate.mockRejectedValueOnce(new Error('offline'));
    render(<NativePushRegistration />);
    await waitFor((): void => expect(mockPostMessage).toHaveBeenCalledTimes(1));
    emit({ userId: 'alice', status: 'registered', fid: 'fid-1' });
    await waitFor((): void =>
      expect(window.alert).toHaveBeenCalledWith(PUSH_COPY.failed),
    );
    emit({ userId: 'alice', status: 'registered', fid: 'fid-1' });
    await waitFor((): void => expect(mockUpdate).toHaveBeenCalledTimes(2));
  });
});
