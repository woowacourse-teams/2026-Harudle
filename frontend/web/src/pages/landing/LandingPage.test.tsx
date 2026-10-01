import { DIARY_GENERATING_COPY } from '../diary-generating/copy';
import {
  afterEach,
  beforeEach,
  describe,
  expect,
  it,
  jest,
} from '@jest/globals';
import { act, fireEvent, render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import LandingPage from './LandingPage';
import { GuestTrialAlreadyUsedError } from './guestTrialErrors';
import type { GuestDiaryRequest, GuestDiaryResponse } from './guestTrialApi';
import { getKoreanToday } from './guestDiaryValidation';
import type { GuestDiaryCreationState } from './useGuestDiaryCreation';

type SubmitGuestDiary = (request: GuestDiaryRequest) => Promise<void>;

const mockSubmitDiary = { current: jest.fn<SubmitGuestDiary>() };
const mockRetryDiary = { current: jest.fn(async () => {}) };
const mockTrack = jest.fn();
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
jest.mock('../../posthog/useAnalytics', () => ({
  useAnalytics: () => ({ track: mockTrack }),
}));
jest.mock('../diary-generating/DiaryGeneratingPage', () => ({
  FINAL_STEP: 5,
}));
jest.mock('react-router', () => ({
  useLocation: () => ({ state: null }),
  useNavigate: () => jest.fn(),
}));

jest.mock('./assets/chat-me.png', () => 'chat-me.png');
jest.mock('./assets/chat-friend.png', () => 'chat-friend.png');
jest.mock('./assets/slack-toothpaste.png', () => 'slack-toothpaste.png');
jest.mock('./assets/slack-favorite.png', () => 'slack-favorite.png');
jest.mock('./assets/slack-song-quiz.png', () => 'slack-song-quiz.png');
jest.mock('./assets/slack-chorok.png', () => 'slack-chorok.png');
jest.mock('./assets/slack-iq.png', () => 'slack-iq.png');
jest.mock('./assets/slack-ihyun.png', () => 'slack-ihyun.png');
jest.mock('./assets/friends-birthday.png', () => 'friends-birthday.png');
jest.mock(
  './assets/work-different-pages.png',
  () => 'work-different-pages.png',
);
jest.mock('./assets/school-presentation.png', () => 'school-presentation.png');
jest.mock('../../assets/images/harudle-logo.png', () => 'harudle-logo.png');
jest.mock('../../assets/icons/kakao.svg', () => 'kakao.svg');
jest.mock('../../assets/icons/check.svg', () => 'check.svg');
jest.mock(
  '../../assets/images/login-shared-comic.png',
  () => 'login-shared-comic.png',
);
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
beforeEach(() => {
  mockCreationState.current = { status: 'writing' };
  mockSubmitDiary.current = jest.fn<SubmitGuestDiary>();
  mockRetryDiary.current = jest.fn(async () => {});
  mockTrack.mockReset();
});

afterEach(() => {
  jest.clearAllTimers();
  jest.useRealTimers();
});

describe('게스트 체험 랜딩 작성 화면', () => {
  it('브랜드 소개와 1회 체험 입력을 같은 랜딩에 보여준다', () => {
    render(<LandingPage />);
    expect(
      screen.getByRole('heading', {
        level: 1,
        name: /우리끼리 통하는\s*네컷만화/,
      }),
    ).toBeInTheDocument();
    expect(
      screen.getByRole('form', { name: /최근에 같이 웃었던\s*순간이 있나요/ }),
    ).toBeInTheDocument();
    expect(
      screen.getByRole('textbox', { name: '네컷만화로 만들 내용' }),
    ).toHaveAttribute('maxLength', '300');
    expect(
      screen.getByRole('button', { name: '네컷만화 만들기' }),
    ).toBeInTheDocument();
    expect(document.querySelector('input[type="date"]')).toBeNull();
  });

  it('체험 준비 중에는 소개를 유지하면서 작성과 생성은 노출하지 않는다', () => {
    render(<LandingPage entryFeedback={<p>체험을 준비하고 있어요</p>} />);
    expect(
      screen.getByRole('heading', {
        level: 1,
        name: /우리끼리 통하는\s*네컷만화/,
      }),
    ).toBeInTheDocument();
    expect(screen.getByText('체험을 준비하고 있어요')).toBeInTheDocument();
    expect(screen.queryByRole('textbox')).not.toBeInTheDocument();
    expect(mockSubmitDiary.current).not.toHaveBeenCalled();
  });

  it('입력한 내용을 별도 작성 페이지 이동 없이 바로 생성한다', async () => {
    const user = userEvent.setup();
    render(<LandingPage />);

    await user.type(
      screen.getByRole('textbox', { name: '네컷만화로 만들 내용' }),
      '친구와 산책하며 오래 웃었던 하루였다.',
    );
    await user.click(screen.getByRole('button', { name: '네컷만화 만들기' }));

    expect(mockSubmitDiary.current).toHaveBeenCalledWith({
      diaryDate: getKoreanToday(),
      sourceText: '친구와 산책하며 오래 웃었던 하루였다.',
    });
    expect(mockTrack).toHaveBeenCalledWith(
      'landing_trial_diary_create_clicked',
    );
  });

  it('빈 내용으로 누르면 생성하지 않고 입력창에 초점을 주며 안내한다', async () => {
    const user = userEvent.setup();
    render(<LandingPage />);
    const textbox = screen.getByRole('textbox', {
      name: '네컷만화로 만들 내용',
    });

    await user.click(screen.getByRole('button', { name: '네컷만화 만들기' }));

    expect(mockSubmitDiary.current).not.toHaveBeenCalled();
    expect(textbox).toHaveFocus();
    expect(screen.getByRole('alert')).toHaveTextContent(
      '있었던 순간을 적어주세요',
    );
    expect(textbox).toHaveAttribute('aria-invalid', 'true');
  });

  it('10자 미만 입력은 생성하지 않고 다시 입력하면 오류 안내를 해제한다', async () => {
    const user = userEvent.setup();
    render(<LandingPage />);
    const textbox = screen.getByRole('textbox', {
      name: '네컷만화로 만들 내용',
    });

    await user.type(textbox, '짧은 일기');
    await user.click(screen.getByRole('button', { name: '네컷만화 만들기' }));

    expect(mockSubmitDiary.current).not.toHaveBeenCalled();
    expect(mockTrack).not.toHaveBeenCalled();
    expect(textbox).toHaveFocus();
    expect(
      screen.getByText('오늘의 이야기를 10자 이상 적어주세요'),
    ).toBeInTheDocument();
    expect(textbox).toHaveAttribute(
      'aria-describedby',
      'guest-diary-source-text-error',
    );

    await user.clear(textbox);
    await user.type(textbox, '친구와 함께 오래 산책했던 하루였다');

    expect(
      screen.queryByText('오늘의 이야기를 10자 이상 적어주세요'),
    ).not.toBeInTheDocument();
    expect(
      screen.queryByText('10자 이상 300자 이하로 적어주세요'),
    ).not.toBeInTheDocument();
    expect(textbox).not.toHaveAttribute('aria-describedby');
  });

  it('생성 중에는 랜딩을 유지한 채 작성 카드만 대기 화면으로 바꾼다', () => {
    mockCreationState.current = { status: 'generating' };

    render(<LandingPage />);

    expect(
      screen.getByRole('heading', {
        level: 1,
        name: /우리끼리 통하는\s*네컷만화/,
      }),
    ).toBeInTheDocument();
    expect(
      screen.queryByRole('form', {
        name: /최근에 같이 웃었던\s*순간이 있나요/,
      }),
    ).not.toBeInTheDocument();
    expect(
      screen.getByRole('status', { name: '네컷만화를 만들고 있어요' }),
    ).toBeInTheDocument();
    expect(
      screen.getByText('완성되면 이곳에서 네컷만화를 보여드릴게요.'),
    ).toBeInTheDocument();
    expect(
      screen.getByText(DIARY_GENERATING_COPY.stepLabels[0]),
    ).toBeInTheDocument();
    expect(
      screen.getByText(DIARY_GENERATING_COPY.stepLabels[1]),
    ).toBeInTheDocument();
    expect(
      screen.getByText(DIARY_GENERATING_COPY.stepLabels[2]),
    ).toBeInTheDocument();
    expect(
      screen.getByText(DIARY_GENERATING_COPY.stepLabels[3]),
    ).toBeInTheDocument();
    expect(
      screen.getByText(DIARY_GENERATING_COPY.stepLabels[4]),
    ).toBeInTheDocument();
    expect(
      screen.queryByRole('link', { name: '카카오로 시작하고 계속 만들기' }),
    ).not.toBeInTheDocument();
  });

  it('마지막 생성 단계가 길어지면 추가 대기 안내를 보여준다', () => {
    jest.useFakeTimers();
    mockCreationState.current = { status: 'generating' };

    render(<LandingPage />);

    for (let step = 0; step < 3; step += 1) {
      act(() => {
        jest.advanceTimersByTime(3_000);
      });
    }

    expect(
      screen.getByText('색을 더하고 다듬어 네컷만화를 완성하고 있어요'),
    ).toBeInTheDocument();
    expect(
      screen.queryByText(
        '예상보다 시간이 걸리고 있어요. 조금만 더 기다려주세요.',
      ),
    ).not.toBeInTheDocument();

    act(() => {
      jest.advanceTimersByTime(6_000);
    });

    expect(
      screen.getByText(
        '예상보다 시간이 걸리고 있어요. 조금만 더 기다려주세요.',
      ),
    ).toBeInTheDocument();
  });

  it('이미 사용한 상태에서는 체험 입력을 숨기고 CTA를 로그인 안내로 바꾼다', () => {
    mockCreationState.current = {
      status: 'error',
      error: new GuestTrialAlreadyUsedError(),
    };

    render(<LandingPage />);

    expect(
      screen.getByRole('heading', { name: '게스트 체험을 이미 사용했어요' }),
    ).toBeInTheDocument();
    expect(
      screen.getByRole('button', { name: '로그인하고 계속 만들기' }),
    ).toBeInTheDocument();
    expect(
      screen.queryByRole('form', {
        name: /최근에 같이 웃었던\s*순간이 있나요/,
      }),
    ).not.toBeInTheDocument();
    expect(
      screen.queryByRole('textbox', { name: '네컷만화로 만들 내용' }),
    ).toBeNull();
    expect(
      screen.queryByText('이 체험은 한 번만 사용할 수 있어요.'),
    ).not.toBeInTheDocument();
  });

  it('결과 사진이 모두 로드된 뒤에만 네컷만화와 로그인 안내를 보여준다', () => {
    mockCreationState.current = {
      status: 'success',
      data: guestDiaryResponse,
    };

    render(<LandingPage />);

    expect(
      screen.getByRole('heading', {
        level: 1,
        name: /우리끼리 통하는\s*네컷만화/,
      }),
    ).toBeInTheDocument();
    expect(
      screen.queryByRole('form', {
        name: /최근에 같이 웃었던\s*순간이 있나요/,
      }),
    ).not.toBeInTheDocument();
    expect(
      screen.getByRole('status', { name: '완성한 네컷을 불러오고 있어요' }),
    ).toBeInTheDocument();
    expect(
      screen.queryByRole('link', { name: '카카오로 시작하고 계속 만들기' }),
    ).not.toBeInTheDocument();

    const preloadedResultImage = screen.getByTestId(
      'landing-trial-result-preload',
    );

    fireEvent.load(preloadedResultImage);

    const resultCard = screen.getByRole('article', {
      name: '비에 흠뻑 젖은 하루',
    });

    expect(
      within(resultCard).getByRole('heading', {
        name: '비에 흠뻑 젖은 하루',
      }),
    ).toBeInTheDocument();
    expect(
      within(resultCard).getByRole('img', {
        name: '비에 흠뻑 젖은 하루 네컷만화',
      }),
    ).toHaveAttribute('src', 'guest-result.png');
    expect(
      within(resultCard).getByText(
        '카카오로 로그인하면 다른 순간도 네컷만화로 만들 수 있어요.',
      ),
    ).toBeInTheDocument();
    expect(
      within(resultCard).queryByText(
        '로그인하면 네컷 그림을 계속 만들 수 있어요.',
      ),
    ).not.toBeInTheDocument();
    expect(
      within(resultCard).getByRole('link', {
        name: '카카오로 시작하고 계속 만들기',
      }),
    ).toHaveAttribute('href', '/oauth2/authorization/kakao');
  });

  it('결과 이미지 로드가 실패하면 제목과 로그인 안내를 보여준다', () => {
    mockCreationState.current = {
      status: 'success',
      data: guestDiaryResponse,
    };

    render(<LandingPage />);

    const preloadedResultImage = screen.getByTestId(
      'landing-trial-result-preload',
    );

    fireEvent.error(preloadedResultImage);

    const resultCard = screen.getByRole('article', {
      name: '비에 흠뻑 젖은 하루',
    });

    expect(
      within(resultCard).getByRole('heading', {
        name: '비에 흠뻑 젖은 하루',
      }),
    ).toBeInTheDocument();
    expect(within(resultCard).getByRole('alert')).toHaveTextContent(
      '결과 이미지를 불러오지 못했어요',
    );
    expect(
      within(resultCard).queryByRole('img', {
        name: '비에 흠뻑 젖은 하루 네컷만화',
      }),
    ).not.toBeInTheDocument();
    expect(
      within(resultCard).getByRole('link', {
        name: '카카오로 시작하고 계속 만들기',
      }),
    ).toBeInTheDocument();
  });
});
