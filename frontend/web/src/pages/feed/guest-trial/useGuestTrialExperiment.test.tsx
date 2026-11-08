import {
  afterEach,
  beforeAll,
  beforeEach,
  describe,
  expect,
  it,
  jest,
} from '@jest/globals';
import {
  act,
  fireEvent,
  render,
  screen,
  waitFor,
} from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { useRef } from 'react';
import { ToastProvider } from '../../../shared/ToastContext';
import type { DiaryDetailResponse } from '../../../domain/diary/diaryDetail';
import type { DiaryGenerationStatusResponse } from '../../../domain/diary/diaryGenerate';
import { RequestError } from '../../../shared/api';
import * as guestDiaryValidation from '../../landing/guestDiaryValidation';
import type * as GuestTrialApi from './guestTrialApi';
import useGuestTrialExperiment from './useGuestTrialExperiment';
import GuestTrialNotice from './GuestTrialNotice';
import {
  readGuestTrialDiaryId,
  readGuestTrialGenerationId,
  removeGuestTrialDiaryId,
  removeGuestTrialGenerationId,
} from './guestTrialStorage';
import { GUEST_TRIAL_COPY } from './copy';

const DIARY_ID = '0b9c1c5e-6f0a-4f0e-9a51-6c1c3b2f7d10';
const GENERATION_ID = '3b85d827-72da-4e23-98ef-a97119e441b8';
const DIARY_ID_KEY = 'harudle.guest-trial.diary-id';
const GENERATION_ID_KEY = 'harudle.guest-trial.generation-id';
const GUIDE_SEEN_KEY = 'harudle.guest-trial.guide-seen';
const STORY = '친구가 민트초코에 치즈폼을 올려 먹어보라고 했다.';

const mockSubmit = jest.fn<typeof GuestTrialApi.submitGuestTrialDiary>();
const mockStatus =
  jest.fn<typeof GuestTrialApi.getGuestTrialGenerationStatus>();
const mockIssueSession = jest.fn<typeof GuestTrialApi.issueGuestSession>();
const mockGetDiary = jest.fn<typeof GuestTrialApi.getGuestTrialDiary>();
const mockResolveClaim = jest.fn<typeof GuestTrialApi.resolveGuestTrialClaim>();
const mockTrack = jest.fn();
const mockNavigate = jest.fn();
const mockOriginalCreate = jest.fn();

jest.mock('./guestTrialApi', () => ({
  submitGuestTrialDiary: (...args: Parameters<typeof mockSubmit>) =>
    mockSubmit(...args),
  getGuestTrialGenerationStatus: (...args: Parameters<typeof mockStatus>) =>
    mockStatus(...args),
  issueGuestSession: () => mockIssueSession(),
  getGuestTrialDiary: (...args: Parameters<typeof mockGetDiary>) =>
    mockGetDiary(...args),
  resolveGuestTrialClaim: (...args: Parameters<typeof mockResolveClaim>) =>
    mockResolveClaim(...args),
}));
jest.mock('../../../posthog/useAnalytics', () => ({
  useAnalytics: () => ({ track: mockTrack }),
}));
jest.mock('react-router', () => ({
  useNavigate: () => mockNavigate,
}));
jest.mock('../../diary-detail/DiaryImageDownloadButton', () => ({
  __esModule: true,
  default: () => <button type="button">이미지 저장</button>,
}));
jest.mock('../../../shared/LoadingSpinner', () => ({
  __esModule: true,
  default: () => <span>불러오는 중</span>,
}));
jest.mock('../../../assets/icons/toast-success.svg', () => 'success.svg');
jest.mock('../../../assets/icons/toast-error.svg', () => 'error.svg');
jest.mock('../../../assets/icons/toast-loading.svg', () => 'loading.svg');
jest.mock('../../../assets/icons/bottom-sheet-close.svg', () => 'close.svg');

const guestDiary = {
  id: DIARY_ID,
  diaryDate: '2026-11-06',
  sourceText: STORY,
  createdAt: '2026-11-06T10:00:00Z',
  generation: {
    id: GENERATION_ID,
    status: 'SUCCEEDED',
    title: '나의 최애 음료',
    imageUrl: 'https://example.com/diary.webp',
    imageUrlExpiresAt: '2026-11-06T10:15:00Z',
    completedAt: '2026-11-06T10:01:00Z',
  },
} as const;

const memberDiary: DiaryDetailResponse = {
  ...guestDiary,
  publishedFeedId: null,
};

const succeeded: DiaryGenerationStatusResponse = {
  id: GENERATION_ID,
  status: 'SUCCEEDED',
  diaryId: DIARY_ID,
  errorCode: null,
};

const problem = (status: 401 | 404 | 409 | 503, code: string): RequestError => {
  return new RequestError({
    type: 'about:blank',
    title: code,
    status,
    detail: code,
    instance: '/api/v1/guest/diaries',
    code,
  });
};

/** FeedPage와 같은 방식으로 + 핸들러를 고르고 실험 화면을 렌더링한다. */
const TestFeed = () => {
  const createButtonRef = useRef<HTMLButtonElement>(null);
  const guestTrial = useGuestTrialExperiment({
    isFeedReady: true,
    createButtonRef,
  });

  return (
    <>
      <button
        ref={createButtonRef}
        type="button"
        aria-label="네컷만화 만들기"
        onClick={guestTrial.isActive ? guestTrial.onCreate : mockOriginalCreate}
      />
      {guestTrial.element}
    </>
  );
};

const renderExperiment = (): ReturnType<typeof render> => {
  return render(
    <ToastProvider>
      <GuestTrialNotice />
      <TestFeed />
    </ToastProvider>,
  );
};

// 첫 테스트가 가이드를 본 뒤에는 같은 방문으로 간주돼 체험용 +로 바로 연다.
const openInputFromEntry = async (
  user: ReturnType<typeof userEvent.setup>,
): Promise<void> => {
  await user.click(screen.getByRole('button', { name: '네컷만화 만들기' }));
};

const openInputFromGuide = async (
  user: ReturnType<typeof userEvent.setup>,
): Promise<void> => {
  await user.click(
    await screen.findByRole('button', { name: '네컷만화 만들기 체험 열기' }),
  );
};

/** jsdom은 이미지를 내려받지 않으므로 테스트가 로드 성공·실패 시점을 정한다. */
class FakeImage {
  static instances: FakeImage[] = [];
  onload: (() => void) | null = null;
  onerror: (() => void) | null = null;
  decoding = 'auto';
  naturalWidth = 960;
  naturalHeight = 960;
  src = '';

  constructor() {
    FakeImage.instances.push(this);
  }
}

const finishComicImage = (outcome: 'load' | 'error'): void => {
  const image = FakeImage.instances.at(-1);
  if (!image) throw new Error('불러오는 만화 이미지가 없습니다.');
  act((): void => {
    if (outcome === 'load') image.onload?.();
    else image.onerror?.();
  });
};

const mockDrawImage = jest.fn();

beforeAll((): void => {
  Object.defineProperty(window, 'Image', {
    configurable: true,
    writable: true,
    value: FakeImage,
  });
  // jsdom에는 canvas 그리기가 없어 모자이크 계산에 쓰는 호출만 갖춘다.
  Object.defineProperty(HTMLCanvasElement.prototype, 'getContext', {
    configurable: true,
    value: () => ({
      drawImage: mockDrawImage,
      clearRect: jest.fn(),
      imageSmoothingEnabled: true,
    }),
  });
  // jsdom은 dialog의 모달 API를 제공하지 않아 열림 속성만 바꾼다.
  Object.defineProperties(HTMLDialogElement.prototype, {
    showModal: {
      configurable: true,
      value(this: HTMLDialogElement): void {
        this.setAttribute('open', '');
      },
    },
    close: {
      configurable: true,
      value(this: HTMLDialogElement): void {
        this.removeAttribute('open');
      },
    },
  });
});

beforeEach((): void => {
  mockIssueSession.mockResolvedValue(undefined);
  mockSubmit.mockResolvedValue({
    diaryId: DIARY_ID,
    generationId: GENERATION_ID,
  });
  mockStatus.mockResolvedValue(succeeded);
  mockGetDiary.mockResolvedValue(guestDiary);
});

afterEach((): void => {
  jest.restoreAllMocks();
  removeGuestTrialDiaryId();
  removeGuestTrialGenerationId();
  localStorage.clear();
  sessionStorage.clear();
});

describe('실험 대상이 아닌 방문', (): void => {
  it('로그인 이력이 있으면 기존 + 동작을 그대로 사용한다', async (): Promise<void> => {
    const user = userEvent.setup();
    localStorage.setItem('harudle.has-ever-logged-in', 'true');
    renderExperiment();

    await user.click(screen.getByRole('button', { name: '네컷만화 만들기' }));

    expect(mockOriginalCreate).toHaveBeenCalledTimes(1);
    expect(
      screen.queryByRole('button', { name: '네컷만화 만들기 체험 열기' }),
    ).not.toBeInTheDocument();
    expect(localStorage.getItem(GUIDE_SEEN_KEY)).toBeNull();
  });

  it('이전 방문에서 가이드를 본 브라우저는 기존 + 동작을 사용한다', async (): Promise<void> => {
    const user = userEvent.setup();
    localStorage.setItem(GUIDE_SEEN_KEY, '2026-11-06T10:00:00.000Z');
    renderExperiment();

    await user.click(screen.getByRole('button', { name: '네컷만화 만들기' }));

    expect(mockOriginalCreate).toHaveBeenCalledTimes(1);
  });

  it('로그인한 회원에게 체험 ID가 없으면 claim하지 않는다', (): void => {
    localStorage.setItem('harudle.has-completed-oauth', 'true');
    renderExperiment();

    expect(mockResolveClaim).not.toHaveBeenCalled();
  });
});

describe('비로그인 체험 흐름', (): void => {
  it('가이드에서 만든 만화를 1·4컷(기·결)을 가린 결과로 보여준다', async (): Promise<void> => {
    const user = userEvent.setup();
    renderExperiment();

    await openInputFromGuide(user);
    expect(mockTrack).toHaveBeenCalledWith('guest_trial_guide_viewed');
    expect(localStorage.getItem(GUIDE_SEEN_KEY)).not.toBeNull();

    const field = screen.getByRole('textbox', { name: '오늘의 이야기' });
    await user.type(field, '짧은 글');
    await user.click(
      screen.getByRole('button', { name: '이 이야기로 만화 만들기' }),
    );
    expect(screen.getByRole('alert')).toHaveTextContent('10자 이상');
    expect(mockSubmit).not.toHaveBeenCalled();

    await user.clear(field);
    await user.type(field, STORY);
    await user.click(
      screen.getByRole('button', { name: '이 이야기로 만화 만들기' }),
    );

    expect(mockIssueSession).toHaveBeenCalledTimes(1);
    expect(mockSubmit).toHaveBeenCalledWith({
      request: { diaryDate: expect.any(String), sourceText: STORY },
      idempotencyKey: expect.any(String),
    });
    expect(mockTrack).toHaveBeenCalledWith('guest_trial_submitted');

    await user.click(await screen.findByRole('button', { name: '보러가기' }));
    expect(sessionStorage.getItem(DIARY_ID_KEY)).toBe(DIARY_ID);

    const comic = await screen.findByRole('img', {
      name: '2컷과 3컷을 가린 네컷만화',
    });
    expect(mockGetDiary).toHaveBeenCalledWith(DIARY_ID);
    expect(mockTrack).not.toHaveBeenCalledWith(
      'guest_trial_result_viewed',
      expect.anything(),
    );

    finishComicImage('load');
    expect(mockTrack).toHaveBeenCalledWith('guest_trial_result_viewed', {
      diary_id: DIARY_ID,
    });
    expect(FakeImage.instances.at(-1)?.src).toBe(
      guestDiary.generation.imageUrl,
    );
    // 원본 이미지는 DOM에 두지 않고, 우클릭·드래그 저장 동작을 막는다.
    expect(comic.querySelector('img')).toBeNull();
    expect(comic.querySelector('canvas')).not.toBeNull();
    expect(fireEvent.contextMenu(comic)).toBe(false);
    expect(fireEvent.dragStart(comic)).toBe(false);

    await user.click(
      screen.getByRole('button', { name: GUEST_TRIAL_COPY.loginAction }),
    );
    expect(mockNavigate).toHaveBeenCalledWith('/login');
  });

  it('완료 전에는 작업 ID만 남기고, 조회 실패는 다시 확인할 수 있다', async (): Promise<void> => {
    const user = userEvent.setup();
    mockStatus
      .mockRejectedValueOnce(new TypeError('Failed to fetch'))
      .mockResolvedValueOnce(succeeded);
    renderExperiment();

    await openInputFromEntry(user);
    await user.type(
      screen.getByRole('textbox', { name: '오늘의 이야기' }),
      STORY,
    );
    await user.click(
      screen.getByRole('button', { name: '이 이야기로 만화 만들기' }),
    );

    expect(
      await screen.findByText('만화 상태를 확인하지 못했어요'),
    ).toBeInTheDocument();
    expect(sessionStorage.getItem(DIARY_ID_KEY)).toBeNull();
    expect(sessionStorage.getItem(GENERATION_ID_KEY)).toBe(GENERATION_ID);

    await user.click(screen.getByRole('button', { name: '다시 확인' }));

    expect(
      await screen.findByText('네컷만화가 완성됐어요!'),
    ).toBeInTheDocument();
    expect(sessionStorage.getItem(DIARY_ID_KEY)).toBe(DIARY_ID);
    expect(sessionStorage.getItem(GENERATION_ID_KEY)).toBeNull();
  });

  it('피드를 벗어나거나 새로고침해도 남은 작업 ID로 완료 조회를 이어간다', async (): Promise<void> => {
    sessionStorage.setItem(GENERATION_ID_KEY, GENERATION_ID);
    renderExperiment();

    expect(screen.getByText('네컷만화를 만들고 있어요')).toBeInTheDocument();
    expect(
      await screen.findByText('네컷만화가 완성됐어요!'),
    ).toBeInTheDocument();
    expect(mockStatus).toHaveBeenCalledWith(GENERATION_ID);
    expect(mockSubmit).not.toHaveBeenCalled();
    expect(sessionStorage.getItem(DIARY_ID_KEY)).toBe(DIARY_ID);
    expect(sessionStorage.getItem(GENERATION_ID_KEY)).toBeNull();
  });

  it('접수 실패 문구를 시트에 보여주고 같은 내용의 재시도에 같은 키를 쓴다', async (): Promise<void> => {
    const user = userEvent.setup();
    mockSubmit.mockRejectedValueOnce(
      problem(503, 'GENERATION_CAPACITY_EXCEEDED'),
    );
    renderExperiment();

    await openInputFromEntry(user);
    const field = screen.getByRole('textbox', { name: '오늘의 이야기' });
    await user.type(field, STORY);
    await user.click(
      screen.getByRole('button', { name: '이 이야기로 만화 만들기' }),
    );

    expect(await screen.findByRole('alert')).toHaveTextContent(
      '지금은 만들고 있는 만화가 많아요',
    );
    expect(field).toHaveValue(STORY);

    await user.click(
      screen.getByRole('button', { name: '이 이야기로 만화 만들기' }),
    );
    await screen.findByText('네컷만화가 완성됐어요!');

    const [first, second] = mockSubmit.mock.calls;
    expect(first?.[0].idempotencyKey).toBe(second?.[0].idempotencyKey);
    expect(
      mockTrack.mock.calls.filter(
        ([event]) => event === 'guest_trial_submitted',
      ),
    ).toHaveLength(1);
  });

  it('접수 응답을 잃고 다음 날 피드에 돌아와도 최초 키와 날짜로 재시도한다', async (): Promise<void> => {
    const user = userEvent.setup();
    const today = jest
      .spyOn(guestDiaryValidation, 'getKoreanToday')
      .mockReturnValue('2026-11-06');
    mockSubmit.mockRejectedValueOnce(new TypeError('Failed to fetch'));
    const firstVisit = renderExperiment();
    await openInputFromEntry(user);
    await user.type(
      screen.getByRole('textbox', { name: '오늘의 이야기' }),
      STORY,
    );
    await user.click(
      screen.getByRole('button', { name: '이 이야기로 만화 만들기' }),
    );
    await screen.findByText(GUEST_TRIAL_COPY.submitFailed);
    firstVisit.unmount();

    today.mockReturnValue('2026-11-07');
    renderExperiment();
    await openInputFromEntry(user);
    await user.type(
      screen.getByRole('textbox', { name: '오늘의 이야기' }),
      STORY,
    );
    await user.click(
      screen.getByRole('button', { name: '이 이야기로 만화 만들기' }),
    );
    await screen.findByText(GUEST_TRIAL_COPY.readyTitle);

    const [first, second] = mockSubmit.mock.calls;
    expect(second?.[0]).toEqual(first?.[0]);
    expect(second?.[0].request.diaryDate).toBe('2026-11-06');
    expect(
      mockTrack.mock.calls.filter(
        ([event]) => event === 'guest_trial_submitted',
      ),
    ).toHaveLength(1);
  });

  it('sessionStorage에 쓸 수 없어도 완료 조회와 결과 확인을 이어간다', async (): Promise<void> => {
    const user = userEvent.setup();
    const setItem = Storage.prototype.setItem;
    jest.spyOn(Storage.prototype, 'setItem').mockImplementation(function (
      this: Storage,
      key: string,
      value: string,
    ): void {
      if (key === GENERATION_ID_KEY || key === DIARY_ID_KEY) {
        throw new DOMException('저장 공간이 부족합니다.', 'QuotaExceededError');
      }
      setItem.call(this, key, value);
    });
    renderExperiment();
    await openInputFromEntry(user);
    await user.type(
      screen.getByRole('textbox', { name: '오늘의 이야기' }),
      STORY,
    );
    await user.click(
      screen.getByRole('button', { name: '이 이야기로 만화 만들기' }),
    );

    await user.click(await screen.findByRole('button', { name: '보러가기' }));
    expect(
      await screen.findByRole('img', {
        name: GUEST_TRIAL_COPY.maskedImageLabel,
      }),
    ).toBeInTheDocument();
    expect(mockStatus).toHaveBeenCalledWith(GENERATION_ID);
    expect(readGuestTrialGenerationId()).toBeNull();
    expect(readGuestTrialDiaryId()).toBe(DIARY_ID);
    expect(sessionStorage.getItem(DIARY_ID_KEY)).toBeNull();
  });

  it('완료된 ID가 남아 있으면 비로그인 상태에서 claim 없이 가려진 결과를 다시 연다', async (): Promise<void> => {
    const user = userEvent.setup();
    localStorage.setItem('harudle.has-ever-logged-in', 'true');
    sessionStorage.setItem(DIARY_ID_KEY, DIARY_ID);
    renderExperiment();

    await user.click(screen.getByRole('button', { name: '네컷만화 만들기' }));

    expect(
      await screen.findByRole('img', { name: '2컷과 3컷을 가린 네컷만화' }),
    ).toBeInTheDocument();
    expect(mockOriginalCreate).not.toHaveBeenCalled();
    expect(mockResolveClaim).not.toHaveBeenCalled();
  });
});

describe('가려진 결과 이미지 오류', (): void => {
  it('이미지를 불러오지 못하면 생성 요청 없이 다시 불러온다', async (): Promise<void> => {
    const user = userEvent.setup();
    sessionStorage.setItem(DIARY_ID_KEY, DIARY_ID);
    renderExperiment();

    await user.click(screen.getByRole('button', { name: '네컷만화 만들기' }));
    await screen.findByRole('img', { name: '2컷과 3컷을 가린 네컷만화' });
    finishComicImage('error');

    await user.click(
      await screen.findByRole('button', { name: '다시 불러오기' }),
    );
    await screen.findByRole('img', { name: '2컷과 3컷을 가린 네컷만화' });
    finishComicImage('load');

    expect(mockGetDiary).toHaveBeenCalledTimes(2);
    expect(mockSubmit).not.toHaveBeenCalled();
    expect(mockTrack).toHaveBeenCalledWith('guest_trial_result_viewed', {
      diary_id: DIARY_ID,
    });
  });
});

describe('로그인 후 claim', (): void => {
  beforeEach((): void => {
    localStorage.setItem('harudle.has-completed-oauth', 'true');
    sessionStorage.setItem(DIARY_ID_KEY, DIARY_ID);
  });

  it('회원 소유를 확인하면 ID를 지우고 전체 결과 시트를 연다', async (): Promise<void> => {
    mockResolveClaim.mockResolvedValue({ type: 'claimed', diary: memberDiary });
    renderExperiment();

    expect(
      await screen.findByText('✓ 내 앨범에 저장됐어요'),
    ).toBeInTheDocument();
    expect(
      screen.getByRole('img', { name: '나의 최애 음료' }),
    ).toBeInTheDocument();
    expect(sessionStorage.getItem(DIARY_ID_KEY)).toBeNull();
    expect(mockResolveClaim).toHaveBeenCalledTimes(1);
    expect(mockTrack).toHaveBeenCalledWith('guest_trial_claim_result', {
      diary_id: DIARY_ID,
      success: true,
      error_code: null,
    });
  });

  it('통신 오류는 ID를 유지하고 토스트의 다시 시도로만 재요청한다', async (): Promise<void> => {
    const user = userEvent.setup();
    mockResolveClaim
      .mockResolvedValueOnce({ type: 'retryable', errorCode: 'NETWORK_ERROR' })
      .mockResolvedValueOnce({ type: 'claimed', diary: memberDiary });
    renderExperiment();

    expect(
      await screen.findByText('만화를 가져오지 못했어요'),
    ).toBeInTheDocument();
    expect(sessionStorage.getItem(DIARY_ID_KEY)).toBe(DIARY_ID);
    expect(mockTrack).toHaveBeenCalledWith('guest_trial_claim_result', {
      diary_id: DIARY_ID,
      success: false,
      error_code: 'NETWORK_ERROR',
    });

    await user.click(screen.getByRole('button', { name: '다시 시도' }));

    expect(
      await screen.findByText('✓ 내 앨범에 저장됐어요'),
    ).toBeInTheDocument();
    expect(mockResolveClaim).toHaveBeenCalledTimes(2);
  });

  it.each(['claimed', 'retryable'] as const)(
    'claim 중 피드를 나갔다 돌아오면 기존 요청의 %s 결과를 받는다',
    async (type): Promise<void> => {
      let complete:
        ((outcome: GuestTrialApi.GuestTrialClaimOutcome) => void) | undefined;
      mockResolveClaim.mockReturnValueOnce(
        new Promise((resolve): void => {
          complete = resolve;
        }),
      );
      const firstVisit = renderExperiment();
      expect(mockResolveClaim).toHaveBeenCalledTimes(1);
      firstVisit.unmount();
      renderExperiment();
      expect(mockResolveClaim).toHaveBeenCalledTimes(1);

      await act(async (): Promise<void> => {
        complete?.(
          type === 'claimed'
            ? { type: 'claimed', diary: memberDiary }
            : { type: 'retryable', errorCode: 'NETWORK_ERROR' },
        );
      });

      if (type === 'claimed') {
        expect(
          await screen.findByText(GUEST_TRIAL_COPY.claimedBadge),
        ).toBeInTheDocument();
        expect(sessionStorage.getItem(DIARY_ID_KEY)).toBeNull();
      } else {
        expect(
          await screen.findByRole('button', { name: '다시 시도' }),
        ).toBeInTheDocument();
        expect(sessionStorage.getItem(DIARY_ID_KEY)).toBe(DIARY_ID);
      }
      expect(
        mockTrack.mock.calls.filter(
          ([event]) => event === 'guest_trial_claim_result',
        ),
      ).toHaveLength(1);
    },
  );

  it.each([
    [
      'alreadyClaimed',
      'GUEST_DIARY_ALREADY_CLAIMED',
      '다른 계정에서 이미 가져간 만화예요',
    ],
    [
      'limitExceeded',
      'GUEST_DIARY_CLAIM_LIMIT_EXCEEDED',
      '이미 체험 일기를 가져온 계정이에요',
    ],
  ] as const)(
    '종료 오류 %s는 안내 후 ID를 정리하고 재시도를 제공하지 않는다',
    async (reason, errorCode, message): Promise<void> => {
      mockResolveClaim.mockResolvedValue({
        type: 'terminal',
        reason,
        errorCode,
        message,
      });
      renderExperiment();

      expect(await screen.findByText(message)).toBeInTheDocument();
      expect(sessionStorage.getItem(DIARY_ID_KEY)).toBeNull();
      expect(
        screen.queryByRole('button', { name: '다시 시도' }),
      ).not.toBeInTheDocument();
    },
  );

  it('claim 404는 안내 없이 ID만 정리한다', async (): Promise<void> => {
    mockResolveClaim.mockResolvedValue({
      type: 'ignored',
      errorCode: 'GUEST_DIARY_CLAIM_NOT_FOUND',
    });
    renderExperiment();

    await waitFor((): void => {
      expect(sessionStorage.getItem(DIARY_ID_KEY)).toBeNull();
    });
    expect(screen.queryByRole('button', { name: '다시 시도' })).toBeNull();
    expect(screen.queryByText('✓ 내 앨범에 저장됐어요')).toBeNull();
    expect(mockTrack).toHaveBeenCalledWith('guest_trial_claim_result', {
      diary_id: DIARY_ID,
      success: false,
      error_code: 'GUEST_DIARY_CLAIM_NOT_FOUND',
    });
  });

  it('회원당 한도 초과는 내 앨범으로 이동할 수 있다', async (): Promise<void> => {
    const user = userEvent.setup();
    mockResolveClaim.mockResolvedValue({
      type: 'terminal',
      reason: 'limitExceeded',
      errorCode: 'GUEST_DIARY_CLAIM_LIMIT_EXCEEDED',
      message: '이미 체험 일기를 가져온 계정이에요',
    });
    renderExperiment();

    await user.click(await screen.findByRole('button', { name: '내 앨범' }));

    expect(mockNavigate).toHaveBeenCalledWith('/album');
  });

  it('약관 동의가 필요하면 ID를 유지하고 동의 화면으로 보낸다', async (): Promise<void> => {
    mockResolveClaim.mockResolvedValue({
      type: 'terms',
      errorCode: 'TERMS_ACCEPTANCE_REQUIRED',
    });
    renderExperiment();

    await waitFor((): void => {
      expect(mockNavigate).toHaveBeenCalledWith('/consent');
    });
    expect(sessionStorage.getItem(DIARY_ID_KEY)).toBe(DIARY_ID);
  });
});

describe('피드 밖에서의 완료 알림', (): void => {
  afterEach((): void => {
    window.history.pushState({}, '', '/');
  });

  it('상세 화면에서도 완료 토스트를 띄우고, 보러가기로 피드에서 결과를 연다', async (): Promise<void> => {
    const user = userEvent.setup();
    sessionStorage.setItem(GENERATION_ID_KEY, GENERATION_ID);
    window.history.pushState({}, '', '/feeds/1');
    const { unmount } = render(
      <ToastProvider>
        <GuestTrialNotice />
      </ToastProvider>,
    );

    expect(screen.getByText('네컷만화를 만들고 있어요')).toBeInTheDocument();
    await user.click(await screen.findByRole('button', { name: '보러가기' }));
    expect(mockNavigate).toHaveBeenCalledWith('/');
    expect(sessionStorage.getItem(DIARY_ID_KEY)).toBe(DIARY_ID);

    unmount();
    window.history.pushState({}, '', '/');
    renderExperiment();

    expect(
      await screen.findByRole('img', { name: '2컷과 3컷을 가린 네컷만화' }),
    ).toBeInTheDocument();
  });
});

// 결과 없이 끝난 상태는 같은 방문 동안 유지되므로 파일의 마지막에 확인한다.
describe('결과 없이 끝난 체험', (): void => {
  it('대기 시간 초과는 최종 실패로 안내하고 작업 조회를 끝낸다', async (): Promise<void> => {
    mockStatus.mockResolvedValueOnce({
      id: GENERATION_ID,
      status: 'FAILED',
      diaryId: null,
      errorCode: 'GENERATION_PENDING_TIMEOUT',
    });
    sessionStorage.setItem(GENERATION_ID_KEY, GENERATION_ID);
    renderExperiment();

    expect(
      await screen.findByText('네컷만화 생성 대기 시간이 초과됐어요.'),
    ).toBeInTheDocument();
    expect(readGuestTrialGenerationId()).toBeNull();
    expect(
      screen.queryByRole('button', { name: GUEST_TRIAL_COPY.statusCheckRetry }),
    ).toBeNull();
  });

  it('결과 조회 통신 오류는 ID를 유지하고 다시 불러올 수 있다', async (): Promise<void> => {
    const user = userEvent.setup();
    mockGetDiary.mockRejectedValueOnce(new TypeError('Failed to fetch'));
    sessionStorage.setItem(DIARY_ID_KEY, DIARY_ID);
    renderExperiment();
    await openInputFromEntry(user);

    await user.click(
      await screen.findByRole('button', {
        name: GUEST_TRIAL_COPY.resultReload,
      }),
    );
    expect(
      await screen.findByRole('img', {
        name: GUEST_TRIAL_COPY.maskedImageLabel,
      }),
    ).toBeInTheDocument();
    expect(readGuestTrialDiaryId()).toBe(DIARY_ID);
    expect(mockGetDiary).toHaveBeenCalledTimes(2);
  });

  it.each([401, 404] as const)(
    '결과 조회 %s는 ID를 정리하고 재시도 대신 로그인 동선을 제공한다',
    async (status): Promise<void> => {
      const user = userEvent.setup();
      mockGetDiary.mockRejectedValueOnce(
        problem(status, 'GUEST_DIARY_NOT_FOUND'),
      );
      sessionStorage.setItem(DIARY_ID_KEY, DIARY_ID);
      renderExperiment();
      await openInputFromEntry(user);

      expect(
        await screen.findByText(GUEST_TRIAL_COPY.resultUnavailable),
      ).toBeInTheDocument();
      expect(readGuestTrialDiaryId()).toBeNull();
      expect(
        screen.queryByRole('button', { name: GUEST_TRIAL_COPY.resultReload }),
      ).toBeNull();
      await user.click(screen.getByRole('button', { name: '로그인' }));
      expect(mockNavigate).toHaveBeenCalledWith('/login');
      expect(mockGetDiary).toHaveBeenCalledTimes(1);
    },
  );

  it('남은 작업을 서버에서 찾을 수 없으면 ID를 지우고 기존 + 동작으로 돌아간다', async (): Promise<void> => {
    const user = userEvent.setup();
    mockStatus.mockRejectedValueOnce(problem(404, 'GENERATION_NOT_FOUND'));
    sessionStorage.setItem(GENERATION_ID_KEY, GENERATION_ID);
    renderExperiment();

    expect(
      await screen.findByText('만들던 만화를 찾지 못했어요'),
    ).toBeInTheDocument();
    expect(sessionStorage.getItem(GENERATION_ID_KEY)).toBeNull();

    await user.click(screen.getByRole('button', { name: '네컷만화 만들기' }));
    expect(mockOriginalCreate).toHaveBeenCalledTimes(1);
  });
});
