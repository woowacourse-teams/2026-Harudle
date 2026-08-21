import { beforeEach, describe, expect, it, jest } from '@jest/globals';
import { fireEvent, render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import GuestDiaryWritePage from './GuestDiaryWritePage';
import { createGuestTrialAlreadyUsedError } from './guestTrialErrors';
import type { GuestDiaryRequest, GuestDiaryResponse } from './guestTrialApi';
import { getKoreanToday } from './guestDiaryValidation';
import type { GuestDiaryCreationState } from './useGuestDiaryCreation';

type SubmitGuestDiary = (request: GuestDiaryRequest) => Promise<void>;

const mockSubmitDiary = { current: jest.fn<SubmitGuestDiary>() };
const mockRetryDiary = { current: jest.fn(async () => {}) };
const mockScrollIntoView = { current: jest.fn() };
const mockCreationState = {
  current: { status: 'writing' } as GuestDiaryCreationState,
};

const guestDiaryResponse: GuestDiaryResponse = {
  id: 'guest-diary-id',
  diaryDate: '2026-08-21',
  sourceText: '친구와 산책하며 오래 웃었던 하루였다.',
  createdAt: '2026-08-21T03:00:00Z',
  generation: {
    id: 'guest-generation-id',
    status: 'SUCCEEDED',
    title: '비에 흠뻑 젖은 하루',
    imageUrl: 'guest-result.png',
    imageUrlExpiresAt: '2026-08-21T04:00:00Z',
    completedAt: '2026-08-21T03:01:00Z',
  },
};

jest.mock('./useGuestDiaryCreation', () => ({
  __esModule: true,
  default: () => ({
    creationState: mockCreationState.current,
    submitDiary: mockSubmitDiary.current,
    retryDiary: mockRetryDiary.current,
  }),
}));
jest.mock('../diary-generating/DiaryGeneratingPage', () => ({
  FINAL_STEP: 5,
}));

jest.mock('../../assets/images/harudle-logo.png', () => 'harudle-logo.png');
jest.mock('../../assets/icons/kakao.svg', () => 'kakao.svg');
jest.mock('../../assets/icons/check.svg', () => 'check.svg');
jest.mock('../../assets/images/login-hero.png', () => 'login-hero.png');
jest.mock('../../assets/images/writing-scene.png', () => 'writing-scene.png');
jest.mock(
  '../../assets/images/empty-person-and-dog.png',
  () => 'empty-person-and-dog.png',
);
jest.mock(
  '../../assets/images/loading-animation.webp',
  () => 'loading-animation.webp',
);
jest.mock(
  '../../assets/images/generation-step-1-reading.png',
  () => 'generation-step-1-reading.png',
);
jest.mock(
  '../../assets/images/generation-step-2-writing.png',
  () => 'generation-step-2-writing.png',
);
jest.mock(
  '../../assets/images/generation-step-3-selecting-panels.png',
  () => 'generation-step-3-selecting-panels.png',
);
jest.mock(
  '../../assets/images/generation-step-4-painting.png',
  () => 'generation-step-4-painting.png',
);
jest.mock(
  '../../assets/images/generation-step-5-complete.png',
  () => 'generation-step-5-complete.png',
);
jest.mock(
  '../landing/assets/guest-diary-cat-keyboard.png',
  () => 'guest-diary-cat-keyboard.png',
);
jest.mock(
  '../landing/assets/guest-diary-friend.jpg',
  () => 'guest-diary-friend.jpg',
);
jest.mock(
  '../landing/assets/guest-diary-workout.png',
  () => 'guest-diary-workout.png',
);

beforeEach(() => {
  mockCreationState.current = { status: 'writing' };
  mockSubmitDiary.current = jest.fn<SubmitGuestDiary>();
  mockRetryDiary.current = jest.fn(async () => {});
  mockScrollIntoView.current = jest.fn();
  HTMLElement.prototype.scrollIntoView = mockScrollIntoView.current;
});

describe('게스트 체험 랜딩 작성 화면', () => {
  it('기존 랜딩을 모두 보여준 뒤 마지막 마스코트 아래에서 바로 일기를 작성할 수 있다', () => {
    render(<GuestDiaryWritePage />);

    expect(
      screen.getByRole('heading', {
        name: /일상을 그림으로\s*만들어드려요/,
      }),
    ).toBeInTheDocument();
    expect(
      screen.getByText('찍지 못했던 일상을 그림으로 만들어드립니다'),
    ).toBeInTheDocument();

    const finalMascot = screen.getByRole('img', {
      name: '사람과 강아지가 함께 새로운 네컷을 시작하는 모습',
    });
    const trialForm = screen.getByRole('form', {
      name: '아무 이야기나 적어주세요',
    });

    expect(
      screen.getByText('로그인 없이 체험해보세요! 전부 무료예요'),
    ).toBeInTheDocument();
    expect(
      within(trialForm).queryByText(/사진이 없어도 괜찮아요/),
    ).not.toBeInTheDocument();
    expect(
      finalMascot.compareDocumentPosition(trialForm) &
        Node.DOCUMENT_POSITION_FOLLOWING,
    ).toBe(Node.DOCUMENT_POSITION_FOLLOWING);
    expect(screen.queryByLabelText(/날짜/)).not.toBeInTheDocument();
    expect(document.querySelector('input[type="date"]')).toBeNull();
    expect(screen.getByRole('textbox', { name: '이야기' })).toHaveAttribute(
      'placeholder',
      '상쾌하게 일어나고 보니 오전 11시였다 부랴부랴 짐을 싸고 버스에서 내리니 비가 와서 비에 홀딱 젖었다',
    );
    expect(
      screen.getByRole('button', { name: '네컷 그림 만들기' }),
    ).toBeInTheDocument();
  });

  it('맨 위 무료 체험 버튼을 누르면 입력 폼으로 내려간다', async () => {
    const user = userEvent.setup();
    render(<GuestDiaryWritePage />);

    await user.click(screen.getByRole('button', { name: '무료로 사용해보기' }));

    expect(mockScrollIntoView.current).toHaveBeenCalledWith({
      behavior: 'smooth',
      block: 'start',
    });
  });

  it('입력한 내용을 별도 작성 페이지 이동 없이 바로 생성한다', async () => {
    const user = userEvent.setup();
    render(<GuestDiaryWritePage />);

    await user.type(
      screen.getByRole('textbox', { name: '이야기' }),
      '친구와 산책하며 오래 웃었던 하루였다.',
    );
    await user.click(screen.getByRole('button', { name: '네컷 그림 만들기' }));

    expect(mockSubmitDiary.current).toHaveBeenCalledWith({
      diaryDate: getKoreanToday(),
      sourceText: '친구와 산책하며 오래 웃었던 하루였다.',
    });
  });

  it('생성 중에는 랜딩을 유지한 채 작성 카드만 대기 화면으로 바꾼다', () => {
    mockCreationState.current = { status: 'generating' };

    render(<GuestDiaryWritePage />);

    expect(
      screen.getByRole('heading', {
        name: /일상을 그림으로\s*만들어드려요/,
      }),
    ).toBeInTheDocument();
    expect(
      screen.queryByRole('form', { name: '아무 이야기나 적어주세요' }),
    ).not.toBeInTheDocument();
    expect(
      screen.getByRole('status', { name: '네컷 그림을 만들고 있어요' }),
    ).toBeInTheDocument();
    expect(
      screen.getByText('체험도, 로그인 후 이용도 전부 무료예요'),
    ).toBeInTheDocument();
    expect(screen.getByText('이야기 분석 중')).toBeInTheDocument();
    expect(screen.getByText('장면 구성 중')).toBeInTheDocument();
    expect(screen.getByText('스케치 그리는 중')).toBeInTheDocument();
    expect(screen.getByText('채색하고 마무리 중')).toBeInTheDocument();
    expect(screen.getByText('완료')).toBeInTheDocument();
    expect(
      screen.queryByRole('link', { name: '로그인하고 무료로 더 만들기' }),
    ).not.toBeInTheDocument();
  });

  it('이미 사용한 상태에서는 첫 화면부터 완료 안내만 보여준다', () => {
    mockCreationState.current = {
      status: 'error',
      error: createGuestTrialAlreadyUsedError(),
    };

    render(<GuestDiaryWritePage />);

    expect(
      screen.getByRole('heading', { name: '게스트 체험을 이미 사용했어요' }),
    ).toBeInTheDocument();
    expect(screen.queryByText('무료 네컷 체험')).not.toBeInTheDocument();
    expect(
      screen.queryByRole('form', { name: '아무 이야기나 적어주세요' }),
    ).not.toBeInTheDocument();
    expect(screen.queryByRole('textbox', { name: '이야기' })).toBeNull();
    expect(
      screen.queryByText('이 체험은 한 번만 사용할 수 있어요.'),
    ).not.toBeInTheDocument();
  });

  it('결과 사진이 모두 로드된 뒤에만 사진과 무료 로그인 안내를 보여준다', () => {
    mockCreationState.current = {
      status: 'success',
      data: guestDiaryResponse,
    };

    render(<GuestDiaryWritePage />);

    expect(
      screen.getByRole('heading', {
        name: /일상을 그림으로\s*만들어드려요/,
      }),
    ).toBeInTheDocument();
    expect(
      screen.queryByRole('form', { name: '아무 이야기나 적어주세요' }),
    ).not.toBeInTheDocument();
    expect(
      screen.getByRole('status', { name: '완성한 네컷을 불러오고 있어요' }),
    ).toBeInTheDocument();
    expect(
      screen.queryByRole('link', { name: '로그인하고 무료로 더 만들기' }),
    ).not.toBeInTheDocument();

    const preloadedResultImage = document.querySelector(
      'img[src="guest-result.png"]',
    );

    expect(preloadedResultImage).not.toBeNull();
    fireEvent.load(preloadedResultImage as HTMLImageElement);

    const resultCard = screen.getByRole('article');

    expect(
      within(resultCard).getByRole('heading', {
        name: '비에 흠뻑 젖은 하루',
      }),
    ).toBeInTheDocument();
    expect(
      within(resultCard).getByRole('img', {
        name: '비에 흠뻑 젖은 하루 네컷 그림',
      }),
    ).toHaveAttribute('src', 'guest-result.png');
    expect(
      within(resultCard).getByText(
        '로그인 후에도 네컷 그림 만들기는 전부 무료예요',
      ),
    ).toBeInTheDocument();
    expect(
      within(resultCard).queryByText(
        '로그인하면 네컷 그림을 계속 만들 수 있어요.',
      ),
    ).not.toBeInTheDocument();
    expect(
      within(resultCard).getByRole('link', {
        name: '로그인하고 무료로 더 만들기',
      }),
    ).toHaveAttribute('href', '/oauth2/authorization/kakao');
  });
});
