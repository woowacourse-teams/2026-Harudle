import { beforeEach, describe, expect, it, jest } from '@jest/globals';
import { fireEvent, render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import GuestDiaryResultPage from './GuestDiaryResultPage';
import type { GuestDiaryResponse } from './guestTrialApi';
import type { ApiRequest } from '../../shared/api';

const mockResultRequest = {
  current: { status: 'loading' } as ApiRequest<GuestDiaryResponse>,
};

const guestDiaryResponse: GuestDiaryResponse = {
  id: '7e5cc251-fdde-4cc0-a54e-2c8142750609',
  diaryDate: '2026-08-21',
  sourceText: '친구와 산책하며 오래 웃었던 하루였다.',
  createdAt: '2026-08-21T03:00:00Z',
  generation: {
    id: '3b85d827-72da-4e23-98ef-a97119e441b8',
    status: 'SUCCEEDED',
    title: '비에 흠뻑 젖은 하루',
    imageUrl: 'guest-result.png',
    imageUrlExpiresAt: '2026-08-21T04:00:00Z',
    completedAt: '2026-08-21T03:01:00Z',
  },
};

jest.mock('react-router', () => ({
  useParams: () => ({ diaryId: guestDiaryResponse.id }),
}));

jest.mock('./useGuestDiaryResult', () => ({
  __esModule: true,
  default: () => ({ resultRequest: mockResultRequest.current }),
}));

jest.mock(
  '../../assets/images/loading-animation.webp',
  () => 'loading-animation.webp',
);
jest.mock('../../assets/icons/kakao.svg', () => 'kakao.svg');

beforeEach(() => {
  mockResultRequest.current = {
    status: 'success',
    data: guestDiaryResponse,
  };
});

describe('게스트 일기 결과 화면', () => {
  it('결과 이미지가 로드된 뒤에만 결과 내용과 로그인 CTA를 보여준다', () => {
    render(<GuestDiaryResultPage />);

    expect(
      screen.getByText('완성된 그림 일기를 불러오고 있어요'),
    ).toBeInTheDocument();
    expect(screen.queryByText('오늘의 이야기')).not.toBeInTheDocument();
    expect(
      screen.queryByRole('link', { name: '카카오로 로그인하기' }),
    ).not.toBeInTheDocument();

    fireEvent.load(screen.getByTestId('guest-diary-result-preload'));

    expect(
      screen.getByRole('heading', { name: '비에 흠뻑 젖은 하루' }),
    ).toBeInTheDocument();
    expect(screen.getByText('오늘의 이야기')).toBeInTheDocument();
    expect(
      screen.getByRole('link', { name: '카카오로 로그인하기' }),
    ).toBeInTheDocument();
  });

  it('이미지 로드 실패를 안내하고 같은 결과 이미지를 다시 요청한다', async () => {
    const user = userEvent.setup();
    render(<GuestDiaryResultPage />);

    fireEvent.error(screen.getByTestId('guest-diary-result-preload'));

    expect(
      screen.getByRole('heading', {
        name: '결과 이미지를 불러오지 못했어요',
      }),
    ).toBeInTheDocument();
    expect(
      screen.queryByRole('link', { name: '카카오로 로그인하기' }),
    ).not.toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: '다시 시도' }));

    const retriedImage = screen.getByTestId('guest-diary-result-preload');

    expect(retriedImage).toHaveAttribute('src', 'guest-result.png');
    fireEvent.load(retriedImage);
    expect(
      screen.getByRole('link', { name: '카카오로 로그인하기' }),
    ).toBeInTheDocument();
  });
});
