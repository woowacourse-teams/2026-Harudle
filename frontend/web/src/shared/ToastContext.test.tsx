import { afterEach, describe, expect, it, jest } from '@jest/globals';
import { act, cleanup, renderHook, screen } from '@testing-library/react';
import { ToastProvider, useToastContext } from './ToastContext';

jest.mock('../assets/icons/toast-success.svg', () => 'toast-success.svg');
jest.mock('../assets/icons/toast-error.svg', () => 'toast-error.svg');
jest.mock('../assets/icons/toast-loading.svg', () => 'toast-loading.svg');

afterEach((): void => {
  cleanup();
  jest.useRealTimers();
});

describe('토스트 개수 제한과 무기한 알림 보존', (): void => {
  it('연속 추가 시 가장 오래된 일반 알림을 제거한다', (): void => {
    jest.useFakeTimers();
    const { result } = renderHook(useToastContext, { wrapper: ToastProvider });

    act((): void => {
      result.current.addToast({ type: 'success', message: '첫 알림' }, 2000);
      result.current.addToast(
        { type: 'success', message: '두 번째 알림' },
        2000,
      );
      result.current.addToast(
        { type: 'success', message: '세 번째 알림' },
        2000,
      );
    });

    expect(screen.queryByText('첫 알림')).not.toBeInTheDocument();
    expect(screen.getByText('두 번째 알림')).toBeInTheDocument();
    expect(screen.getByText('세 번째 알림')).toBeInTheDocument();
  });

  it('다른 알림이 추가되어도 생성 알림을 완료 상태로 갱신할 수 있다', (): void => {
    jest.useFakeTimers();
    const { result } = renderHook(useToastContext, { wrapper: ToastProvider });

    act((): void => {
      result.current.addToast(
        { type: 'loading', message: '생성 중' },
        'infinite',
        'generation-1',
      );
    });
    act((): void => {
      result.current.addToast({ type: 'error', message: '첫 오류' }, 2000);
    });
    act((): void => {
      result.current.addToast({ type: 'error', message: '다음 오류' }, 2000);
    });

    expect(screen.getByText('생성 중')).toBeInTheDocument();
    expect(screen.queryByText('첫 오류')).not.toBeInTheDocument();
    expect(screen.getByText('다음 오류')).toBeInTheDocument();

    act((): void => {
      jest.advanceTimersByTime(2000);
      result.current.updateToast('generation-1', {
        type: 'success',
        message: '네컷만화가 완성됐어요!',
        action: { label: '보러가기', onClick: jest.fn() },
      });
    });

    expect(screen.getByText('네컷만화가 완성됐어요!')).toBeInTheDocument();
    expect(
      screen.getByRole('button', { name: '보러가기' }),
    ).toBeInTheDocument();
    expect(screen.queryByText('다음 오류')).not.toBeInTheDocument();
  });

  it('개수 제한에 도달해도 중복 추가가 다른 알림을 제거하지 않는다', (): void => {
    jest.useFakeTimers();
    const { result } = renderHook(useToastContext, { wrapper: ToastProvider });

    act((): void => {
      result.current.addToast({ type: 'success', message: '일반 알림' }, 2000);
      result.current.addToast(
        { type: 'loading', message: '생성 중' },
        'infinite',
        'generation-1',
      );
    });
    act((): void => {
      result.current.addToast(
        { type: 'loading', message: '중복 생성' },
        'infinite',
        'generation-1',
      );
    });

    expect(screen.getByText('일반 알림')).toBeInTheDocument();
    expect(screen.getByText('생성 중')).toBeInTheDocument();
    expect(screen.queryByText('중복 생성')).not.toBeInTheDocument();
  });
});

describe('토스트 ID 지정과 중복 방지', (): void => {
  it('같은 ID로 연속 추가해도 첫 토스트 하나만 표시한다', (): void => {
    const { result } = renderHook(useToastContext, { wrapper: ToastProvider });

    act((): void => {
      const firstId = result.current.addToast(
        { type: 'loading', message: '네컷만화를 만들고 있어요' },
        'infinite',
        'generation-1',
      );
      const secondId = result.current.addToast(
        { type: 'loading', message: '중복 생성 알림' },
        'infinite',
        'generation-1',
      );
      expect(firstId).toBe('generation-1');
      expect(secondId).toBe(firstId);
    });

    expect(result.current.toasts).toHaveLength(1);
    expect(screen.getAllByText('네컷만화를 만들고 있어요')).toHaveLength(1);
    expect(screen.queryByText('중복 생성 알림')).not.toBeInTheDocument();
  });

  it('ID를 생략하면 기존처럼 서로 다른 ID를 자동 생성한다', (): void => {
    const { result } = renderHook(useToastContext, { wrapper: ToastProvider });

    act((): void => {
      const firstId = result.current.addToast(
        { type: 'success', message: '첫 알림' },
        'infinite',
      );
      const secondId = result.current.addToast(
        { type: 'success', message: '다음 알림' },
        'infinite',
      );
      expect(firstId).not.toBe(secondId);
    });

    expect(result.current.toasts).toHaveLength(2);
  });

  it('갱신된 결과 토스트를 같은 ID의 추가 요청이 덮어쓰지 않는다', (): void => {
    const { result } = renderHook(useToastContext, { wrapper: ToastProvider });

    act((): void => {
      result.current.addToast(
        { type: 'loading', message: '생성 중' },
        'infinite',
        'generation-1',
      );
      result.current.updateToast('generation-1', {
        type: 'success',
        message: '네컷만화가 완성됐어요!',
      });
      result.current.addToast(
        { type: 'loading', message: '생성 중' },
        'infinite',
        'generation-1',
      );
    });

    expect(screen.getByText('네컷만화가 완성됐어요!')).toBeInTheDocument();
    expect(screen.queryByText('생성 중')).not.toBeInTheDocument();
    expect(result.current.toasts).toHaveLength(1);
  });

  it('Provider가 새로 마운트되면 같은 ID로 새 토스트를 표시할 수 있다', (): void => {
    const first = renderHook(useToastContext, { wrapper: ToastProvider });
    act((): void => {
      first.result.current.addToast(
        { type: 'loading', message: '이전 알림' },
        'infinite',
        'generation-1',
      );
    });
    first.unmount();

    const second = renderHook(useToastContext, { wrapper: ToastProvider });
    act((): void => {
      second.result.current.addToast(
        { type: 'loading', message: '새 알림' },
        'infinite',
        'generation-1',
      );
    });

    expect(screen.queryByText('이전 알림')).not.toBeInTheDocument();
    expect(screen.getByText('새 알림')).toBeInTheDocument();
  });
});
