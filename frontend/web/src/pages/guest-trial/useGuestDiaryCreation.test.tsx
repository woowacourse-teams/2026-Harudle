import { afterEach, describe, expect, it, jest } from '@jest/globals';
import { act, renderHook, waitFor } from '@testing-library/react';
import type { GuestDiaryResponse } from './guestTrialApi';
import {
  GUEST_DIARY_PENDING_STORAGE_KEY,
  type GuestTrialStorage,
} from './pendingGuestDiary';
import useGuestDiaryCreation from './useGuestDiaryCreation';

type CreateGuestDiary = typeof import('./guestTrialApi').createGuestDiary;

const guestDiaryResponse: GuestDiaryResponse = {
  id: '7e5cc251-fdde-4cc0-a54e-2c8142750609',
  diaryDate: '2026-08-20',
  sourceText: '오늘은 게스트 체험으로 그림 일기를 만들었다.',
  createdAt: '2026-08-20T12:00:00Z',
  generation: {
    id: '3b85d827-72da-4e23-98ef-a97119e441b8',
    status: 'SUCCEEDED',
    title: '처음 만든 그림 일기',
    imageUrl: 'https://example.com/guest-diary.png',
    imageUrlExpiresAt: '2026-08-20T13:00:00Z',
    completedAt: '2026-08-20T12:00:10Z',
  },
};

const request = {
  diaryDate: '2026-08-20',
  sourceText: '오늘은 게스트 체험으로 그림 일기를 만들었다.',
};

const createMemoryStorage = (): GuestTrialStorage => {
  const values = new Map<string, string>();

  return {
    getItem: (key) => values.get(key) ?? null,
    setItem: (key, value) => values.set(key, value),
    removeItem: (key) => {
      values.delete(key);
    },
  };
};

afterEach(() => {
  jest.restoreAllMocks();
});

describe('게스트 일기 생성 상태 머신', () => {
  it('pending 요청을 저장한 뒤 생성하고 성공하면 제거한다', async () => {
    const storage = createMemoryStorage();
    let resolveDiary: (diary: GuestDiaryResponse) => void = () => {};
    const diaryRequest = new Promise<GuestDiaryResponse>((resolve) => {
      resolveDiary = resolve;
    });
    const createDiary = jest
      .fn<CreateGuestDiary>()
      .mockReturnValue(diaryRequest);
    const { result } = renderHook(() =>
      useGuestDiaryCreation({
        enabled: true,
        storage,
        createDiary,
        createIdempotencyKey: () => '7e5cc251-fdde-4cc0-a54e-2c8142750609',
      }),
    );

    expect(result.current.creationState.status).toBe('writing');

    let submission: Promise<void> = Promise.resolve();
    act(() => {
      submission = result.current.submitDiary(request);
    });

    await waitFor(() => {
      expect(result.current.creationState.status).toBe('generating');
    });
    expect(storage.getItem(GUEST_DIARY_PENDING_STORAGE_KEY)).not.toBeNull();

    await act(async () => {
      resolveDiary(guestDiaryResponse);
      await submission;
    });

    expect(createDiary).toHaveBeenCalledWith({
      request,
      idempotencyKey: '7e5cc251-fdde-4cc0-a54e-2c8142750609',
    });
    expect(result.current.creationState).toEqual({
      status: 'success',
      data: guestDiaryResponse,
    });
    expect(storage.getItem(GUEST_DIARY_PENDING_STORAGE_KEY)).toBeNull();
  });

  it('네트워크 실패 후 재시도해도 같은 Idempotency Key를 사용한다', async () => {
    const storage = createMemoryStorage();
    const createDiary = jest
      .fn<CreateGuestDiary>()
      .mockRejectedValueOnce(new TypeError('Failed to fetch'))
      .mockResolvedValueOnce(guestDiaryResponse);
    const { result } = renderHook(() =>
      useGuestDiaryCreation({
        enabled: true,
        storage,
        createDiary,
        createIdempotencyKey: () => '7e5cc251-fdde-4cc0-a54e-2c8142750609',
      }),
    );

    await act(async () => {
      await result.current.submitDiary(request);
    });

    expect(result.current.creationState.status).toBe('error');
    expect(storage.getItem(GUEST_DIARY_PENDING_STORAGE_KEY)).not.toBeNull();

    await act(async () => {
      await result.current.retryDiary();
    });

    expect(createDiary).toHaveBeenNthCalledWith(1, {
      request,
      idempotencyKey: '7e5cc251-fdde-4cc0-a54e-2c8142750609',
    });
    expect(createDiary).toHaveBeenNthCalledWith(2, {
      request,
      idempotencyKey: '7e5cc251-fdde-4cc0-a54e-2c8142750609',
    });
    expect(result.current.creationState.status).toBe('success');
  });

  it('새로고침 후 pending 요청을 같은 키로 자동 복원한다', async () => {
    const storage = createMemoryStorage();
    storage.setItem(
      GUEST_DIARY_PENDING_STORAGE_KEY,
      JSON.stringify({
        idempotencyKey: '7e5cc251-fdde-4cc0-a54e-2c8142750609',
        request,
      }),
    );
    const createDiary = jest
      .fn<CreateGuestDiary>()
      .mockResolvedValue(guestDiaryResponse);

    const { result } = renderHook(() =>
      useGuestDiaryCreation({ enabled: true, storage, createDiary }),
    );

    await waitFor(() => {
      expect(result.current.creationState.status).toBe('success');
    });
    expect(createDiary).toHaveBeenCalledTimes(1);
    expect(createDiary).toHaveBeenCalledWith({
      request,
      idempotencyKey: '7e5cc251-fdde-4cc0-a54e-2c8142750609',
    });
  });

  it('손상된 pending 요청은 폐기하고 작성 상태로 복구한다', () => {
    const storage = createMemoryStorage();
    storage.setItem(
      GUEST_DIARY_PENDING_STORAGE_KEY,
      JSON.stringify({
        idempotencyKey: 'not-a-uuid',
        request: { diaryDate: 'invalid-date', sourceText: '' },
      }),
    );
    const createDiary = jest.fn<CreateGuestDiary>();

    const { result } = renderHook(() =>
      useGuestDiaryCreation({ enabled: true, storage, createDiary }),
    );

    expect(result.current.creationState.status).toBe('writing');
    expect(createDiary).not.toHaveBeenCalled();
    expect(storage.getItem(GUEST_DIARY_PENDING_STORAGE_KEY)).toBeNull();
  });
});
