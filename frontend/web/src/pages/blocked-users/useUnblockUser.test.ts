import { beforeEach, describe, expect, it, jest } from '@jest/globals';
import { act, renderHook } from '@testing-library/react';
import { setUserBlocked } from '../../domain/blocks/blocks';
import useUnblockUser from './useUnblockUser';

jest.mock('../../domain/blocks/blocks', () => ({
  setUserBlocked: jest.fn(),
}));

beforeEach((): void => {
  jest.mocked(setUserBlocked).mockReset();
});

describe('차단 해제 요청', (): void => {
  it('성공 후 호출부의 목록 갱신이 끝날 때까지 진행 상태를 유지한다', async (): Promise<void> => {
    let finishUnblock: () => void = (): void => undefined;
    let finishRefresh: () => void = (): void => undefined;
    jest.mocked(setUserBlocked).mockImplementationOnce(
      (): Promise<void> =>
        new Promise((resolve): void => {
          finishUnblock = resolve;
        }),
    );
    const onSuccess = jest.fn<() => Promise<void>>().mockImplementation(
      (): Promise<void> =>
        new Promise((resolve): void => {
          finishRefresh = resolve;
        }),
    );
    const onError = jest.fn<() => void>();
    const { result } = renderHook(useUnblockUser);
    let execution: Promise<void> = Promise.resolve();
    act((): void => {
      execution = result.current.execute('user-1', { onSuccess, onError });
    });
    expect(setUserBlocked).toHaveBeenCalledTimes(1);
    expect(setUserBlocked).toHaveBeenCalledWith('user-1', false);
    expect(result.current.pendingUserId).toBe('user-1');

    await act(async (): Promise<void> => finishUnblock());
    expect(onSuccess).toHaveBeenCalledTimes(1);
    expect(result.current.pendingUserId).toBe('user-1');
    await act(async (): Promise<void> => {
      finishRefresh();
      await execution;
    });
    expect(result.current.pendingUserId).toBeNull();
    expect(onError).not.toHaveBeenCalled();
  });

  it('해제 실패를 알리고 진행 상태를 정리해 같은 대상을 다시 요청할 수 있다', async (): Promise<void> => {
    jest
      .mocked(setUserBlocked)
      .mockRejectedValueOnce(new Error('해제 실패'))
      .mockResolvedValueOnce(undefined);
    const onSuccess = jest.fn<() => void>();
    const onError = jest.fn<() => void>();
    const { result } = renderHook(useUnblockUser);
    await act(async (): Promise<void> => {
      await result.current.execute('user-1', { onSuccess, onError });
    });
    expect(onError).toHaveBeenCalledTimes(1);
    expect(onSuccess).not.toHaveBeenCalled();
    expect(result.current.pendingUserId).toBeNull();

    await act(async (): Promise<void> => {
      await result.current.execute('user-1', { onSuccess, onError });
    });
    expect(setUserBlocked).toHaveBeenCalledTimes(2);
    expect(onSuccess).toHaveBeenCalledTimes(1);
    expect(onError).toHaveBeenCalledTimes(1);
    expect(result.current.pendingUserId).toBeNull();
  });

  it('화면 이탈 뒤 해제 성공도 호출부에 알려 캐시 정리를 실행할 수 있게 한다', async (): Promise<void> => {
    let finishUnblock: () => void = (): void => undefined;
    jest.mocked(setUserBlocked).mockImplementationOnce(
      (): Promise<void> =>
        new Promise((resolve): void => {
          finishUnblock = resolve;
        }),
    );
    const onSuccess = jest.fn<() => void>();
    const onError = jest.fn<() => void>();
    const { result, unmount } = renderHook(useUnblockUser);
    let execution: Promise<void> = Promise.resolve();
    act((): void => {
      execution = result.current.execute('user-1', { onSuccess, onError });
    });
    unmount();
    await act(async (): Promise<void> => {
      finishUnblock();
      await execution;
    });
    expect(onSuccess).toHaveBeenCalledTimes(1);
    expect(onError).not.toHaveBeenCalled();
  });

  it('화면 이탈 뒤 도착한 해제 실패는 오류 콜백을 호출하지 않는다', async (): Promise<void> => {
    let failUnblock: (error: Error) => void = (): void => undefined;
    jest.mocked(setUserBlocked).mockImplementationOnce(
      (): Promise<void> =>
        new Promise((_resolve, reject): void => {
          failUnblock = reject;
        }),
    );
    const onSuccess = jest.fn<() => void>();
    const onError = jest.fn<() => void>();
    const { result, unmount } = renderHook(useUnblockUser);
    let execution: Promise<void> = Promise.resolve();
    act((): void => {
      execution = result.current.execute('user-1', { onSuccess, onError });
    });
    unmount();
    await act(async (): Promise<void> => {
      failUnblock(new Error('해제 실패'));
      await execution;
    });
    expect(onSuccess).not.toHaveBeenCalled();
    expect(onError).not.toHaveBeenCalled();
  });
});
