import { StrictMode } from 'react';
import { describe, expect, it, jest } from '@jest/globals';
import { act, renderHook, waitFor } from '@testing-library/react';
import type { GuestDiaryResponse } from './guestTrialApi';
import useGuestDiaryResult from './useGuestDiaryResult';

type GetGuestDiary = typeof import('./guestTrialApi').getGuestDiary;

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

describe('게스트 일기 결과 조회', () => {
  it('StrictMode에서도 결과를 한 번만 조회하고 복원한다', async () => {
    const getDiary = jest
      .fn<GetGuestDiary>()
      .mockResolvedValue(guestDiaryResponse);

    const { result } = renderHook(
      () =>
        useGuestDiaryResult({
          diaryId: guestDiaryResponse.id,
          getDiary,
        }),
      { wrapper: StrictMode },
    );

    await waitFor(() => {
      expect(result.current.resultRequest.status).toBe('success');
    });
    expect(getDiary).toHaveBeenCalledTimes(1);
    expect(getDiary).toHaveBeenCalledWith(guestDiaryResponse.id);
  });

  it('일기 ID가 없으면 조회 요청을 보내지 않는다', async () => {
    const getDiary = jest.fn<GetGuestDiary>();

    const { result } = renderHook(() =>
      useGuestDiaryResult({ diaryId: undefined, getDiary }),
    );

    await waitFor(() => {
      expect(result.current.resultRequest.status).toBe('error');
    });
    expect(getDiary).not.toHaveBeenCalled();
  });

  it('Error가 아닌 조회 실패도 오류 상태로 전환한다', async () => {
    const getDiary = jest
      .fn<GetGuestDiary>()
      .mockRejectedValue('unknown failure');
    const { result } = renderHook(() =>
      useGuestDiaryResult({ diaryId: guestDiaryResponse.id, getDiary }),
    );

    await waitFor(() => {
      expect(result.current.resultRequest).toEqual({
        status: 'error',
        error: new Error('게스트 일기 결과를 불러오지 못했습니다'),
      });
    });
  });

  it('결과 재시도 시 최신 이미지 URL을 다시 조회한다', async () => {
    const refreshedDiary = {
      ...guestDiaryResponse,
      generation: {
        ...guestDiaryResponse.generation,
        imageUrl: 'https://example.com/refreshed-guest-diary.png',
      },
    };
    const getDiary = jest
      .fn<GetGuestDiary>()
      .mockResolvedValueOnce(guestDiaryResponse)
      .mockResolvedValueOnce(refreshedDiary);
    const { result } = renderHook(() =>
      useGuestDiaryResult({
        diaryId: guestDiaryResponse.id,
        getDiary,
      }),
    );

    await waitFor(() => {
      expect(result.current.resultRequest.status).toBe('success');
    });

    act(() => {
      result.current.retryResult();
    });

    await waitFor(() => {
      expect(result.current.resultRequest).toEqual({
        status: 'success',
        data: refreshedDiary,
      });
    });
    expect(getDiary).toHaveBeenCalledTimes(2);
  });
});
