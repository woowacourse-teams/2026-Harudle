import { beforeEach, describe, expect, it, jest } from '@jest/globals';
import { render, screen } from '@testing-library/react';
import StreakSummaryCard from './StreakSummaryCard';

jest.mock(
  '../../../assets/images/dog-streak-diary.png',
  () => 'dog-streak-diary.png',
);

const mockAuthFetch = jest.fn<(...args: unknown[]) => Promise<Response>>();

jest.mock('../../../shared/auth', () => ({
  authFetch: (...args: unknown[]) => mockAuthFetch(...args),
}));

const createJsonResponse = (data: unknown): Response =>
  ({
    ok: true,
    status: 200,
    json: async () => data,
  }) as Response;

const renderCard = () => render(<StreakSummaryCard />);

describe('StreakSummaryCard', () => {
  beforeEach(() => {
    mockAuthFetch.mockReset();
    window.localStorage.clear();
  });

  it('현재 streak를 조회해 연속 기록과 오늘 기록 상태를 표시한다', async () => {
    mockAuthFetch.mockResolvedValueOnce(
      createJsonResponse({
        streakCount: 5,
        recordedToday: false,
        days: [
          {
            date: '2026-08-12',
            items: [
              {
                id: 'diary-1',
                title: '오늘의 기록',
                thumbnailUrl: 'https://example.com/diary.png',
              },
            ],
          },
        ],
      }),
    );

    renderCard();

    expect(await screen.findByText('5일째')).toBeInTheDocument();
    expect(screen.getByText('오늘도 이어가 볼까요?')).toBeInTheDocument();
    expect(
      screen.getByRole('img', { name: '연속 기록을 이어가는 강아지' }),
    ).toHaveAttribute('src', 'dog-streak-diary.png');
    expect(mockAuthFetch).toHaveBeenCalledWith(
      '/api/v1/diaries/current-streak',
    );
  });

  it('오늘 기록 완료 응답은 캐시해 홈 재진입 시 다시 조회하지 않는다', async () => {
    mockAuthFetch.mockResolvedValueOnce(
      createJsonResponse({
        streakCount: 6,
        recordedToday: true,
        days: [],
      }),
    );

    const firstRender = renderCard();
    expect(await screen.findByText('6일째')).toBeInTheDocument();
    firstRender.unmount();

    renderCard();

    expect(await screen.findByText('6일째')).toBeInTheDocument();
    expect(mockAuthFetch).toHaveBeenCalledTimes(1);
  });

  it('오늘 기록이 완료되지 않으면 홈 재진입 시 다시 조회한다', async () => {
    mockAuthFetch.mockResolvedValue(
      createJsonResponse({
        streakCount: 5,
        recordedToday: false,
        days: [],
      }),
    );

    const firstRender = renderCard();
    expect(await screen.findByText('5일째')).toBeInTheDocument();
    firstRender.unmount();

    renderCard();

    expect(await screen.findByText('5일째')).toBeInTheDocument();
    expect(mockAuthFetch).toHaveBeenCalledTimes(2);
  });

  it('응답의 중첩 구조가 올바르지 않으면 안전한 오류 문구를 표시한다', async () => {
    mockAuthFetch.mockResolvedValueOnce(
      createJsonResponse({
        streakCount: 1,
        recordedToday: false,
        days: [{ date: '2026-08-12', items: [{ id: 'diary-1' }] }],
      }),
    );

    renderCard();

    expect(
      await screen.findByText('잠시 후 다시 확인해 주세요.'),
    ).toBeInTheDocument();
  });
});
