import { act, renderHook } from '@testing-library/react';
import { beforeEach, describe, expect, it, jest } from '@jest/globals';
import { setUserBlocked } from '../../domain/blocks/blocks';
import useBlockUser from './useBlockUser';
jest.mock('../../domain/blocks/blocks', () => ({ setUserBlocked: jest.fn() }));
beforeEach((): void => {
  jest.mocked(setUserBlocked).mockReset();
});
describe('차단 요청 훅', (): void => {
  it('같은 순간에 중복 실행·초기화해도 진행 중 요청과 실패 결과를 유지한다', async (): Promise<void> => {
    let rejectRequest: (error: Error) => void = (): void => undefined;
    jest.mocked(setUserBlocked).mockImplementationOnce(
      (): Promise<void> =>
        new Promise((_resolve, reject): void => {
          rejectRequest = reject;
        }),
    );
    const onSuccess = jest.fn();
    const { result } = renderHook(() => useBlockUser({ userId: 'author-1' }));
    let first: Promise<void> | undefined;
    act((): void => {
      first = result.current.execute({ onSuccess });
      void result.current.execute({ onSuccess });
      result.current.reset();
    });
    expect(setUserBlocked).toHaveBeenCalledTimes(1);
    expect(result.current.request.status).toBe('loading');
    await act(async (): Promise<void> => {
      rejectRequest(new Error('차단 저장 실패'));
      await first;
    });
    expect(result.current.request).toMatchObject({
      status: 'error',
      error: new Error('차단 저장 실패'),
    });
    expect(onSuccess).not.toHaveBeenCalled();
    act((): void => result.current.reset());
    expect(result.current.request.status).toBe('idle');
  });
  it('저장에 성공했을 때만 호출자가 정한 후속 처리를 실행한다', async (): Promise<void> => {
    jest.mocked(setUserBlocked).mockResolvedValueOnce(undefined);
    const onSuccess = jest.fn();
    const { result } = renderHook(() => useBlockUser({ userId: 'author-1' }));
    await act(async (): Promise<void> => {
      await result.current.execute({ onSuccess });
    });
    expect(setUserBlocked).toHaveBeenCalledWith('author-1', true);
    expect(result.current.request.status).toBe('success');
    expect(onSuccess).toHaveBeenCalledTimes(1);
  });
  it('없는 작성자는 차단 요청이나 후속 처리를 실행하지 않는다', async (): Promise<void> => {
    const onSuccess = jest.fn();
    const { result } = renderHook(() => useBlockUser({ userId: null }));
    await act(async (): Promise<void> => {
      await result.current.execute({ onSuccess });
    });
    expect(setUserBlocked).not.toHaveBeenCalled();
    expect(onSuccess).not.toHaveBeenCalled();
    expect(result.current.request.status).toBe('idle');
  });
});
