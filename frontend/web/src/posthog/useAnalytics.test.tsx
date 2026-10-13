import {
  afterEach,
  beforeEach,
  describe,
  expect,
  it,
  jest,
} from '@jest/globals';
import { act, renderHook, waitFor } from '@testing-library/react';
import { setAccessToken } from '../shared/auth';
import useFeedList from '../pages/feed/useFeedList';
import type { FeedListResponse, getFeedList } from '../domain/feeds/feedList';
import { useAnalytics } from './useAnalytics';
import useNicknameUpdate from '../pages/setting/useNicknameUpdate';
import type { updateUserNickname } from '../domain/user/profile';

const mockCapture =
  jest.fn<(event: string, properties?: Record<string, unknown>) => void>();
const mockReset = jest.fn<() => void>();
const mockIdentifyCurrentUser = jest.fn<() => Promise<void>>();
const mockSetPersonProperties =
  jest.fn<(properties: { name: string }) => void>();
const mockPostHog = {
  capture: mockCapture,
  reset: mockReset,
  setPersonProperties: mockSetPersonProperties,
};
let mockIsEnabled = true;

jest.mock('@posthog/react', () => ({
  usePostHog: () => mockPostHog,
}));
jest.mock('./posthog', () => ({
  get isPostHogEnabled(): boolean {
    return mockIsEnabled;
  },
  identifyCurrentUserForPostHog: (): Promise<void> => mockIdentifyCurrentUser(),
}));

const mockGetFeedList = jest.fn<typeof getFeedList>();
const mockUpdateUserNickname = jest.fn<typeof updateUserNickname>();
jest.mock('../domain/user/profile', () => ({
  updateUserNickname: (nickname: string): Promise<void> =>
    mockUpdateUserNickname(nickname),
}));
jest.mock('../domain/feeds/feedList', () => ({
  getFeedList: (
    ...args: Parameters<typeof getFeedList>
  ): ReturnType<typeof getFeedList> => mockGetFeedList(...args),
}));

const emptyResponse: FeedListResponse = {
  items: [],
  nextCursor: null,
  hasNext: false,
};

beforeEach((): void => {
  mockIsEnabled = false;
  const { result, unmount } = renderHook((): ReturnType<typeof useAnalytics> =>
    useAnalytics(),
  );
  act((): void => result.current.resetUser());
  unmount();
  mockIsEnabled = true;
  localStorage.clear();
  setAccessToken(null);
  mockGetFeedList.mockReset().mockResolvedValue(emptyResponse);
  mockIdentifyCurrentUser.mockReset().mockResolvedValue();
  mockUpdateUserNickname.mockReset().mockResolvedValue();
  mockSetPersonProperties.mockReset();
});

describe('닉네임 변경과 분석 프로필', (): void => {
  it('서버 저장에 성공한 닉네임을 표시 이름에 반영한다', async (): Promise<void> => {
    const { result } = renderHook((): ReturnType<typeof useNicknameUpdate> =>
      useNicknameUpdate(),
    );
    await act(async (): Promise<void> => {
      expect(await result.current.execute('새닉네임')).toBe(true);
    });
    expect(mockSetPersonProperties).toHaveBeenCalledWith({ name: '새닉네임' });
    expect(result.current.request.status).toBe('success');
  });

  it('서버 저장 실패 시 기존 분석 프로필 이름을 유지한다', async (): Promise<void> => {
    mockUpdateUserNickname.mockRejectedValueOnce(new Error('저장 실패'));
    const { result } = renderHook((): ReturnType<typeof useNicknameUpdate> =>
      useNicknameUpdate(),
    );
    await act(async (): Promise<void> => {
      expect(await result.current.execute('새닉네임')).toBe(false);
    });
    expect(mockSetPersonProperties).not.toHaveBeenCalled();
    expect(result.current.request.status).toBe('error');
  });

  it('분석 프로필 갱신 실패가 성공한 닉네임 저장을 실패로 바꾸지 않는다', async (): Promise<void> => {
    mockSetPersonProperties.mockImplementationOnce((): never => {
      throw new Error('SDK 실패');
    });
    const { result } = renderHook((): ReturnType<typeof useNicknameUpdate> =>
      useNicknameUpdate(),
    );
    await act(async (): Promise<void> => {
      expect(await result.current.execute('새닉네임')).toBe(true);
    });
    expect(result.current.request.status).toBe('success');
  });

  it('수집이 비활성화되어도 닉네임 저장은 정상 처리한다', async (): Promise<void> => {
    mockIsEnabled = false;
    const { result } = renderHook((): ReturnType<typeof useNicknameUpdate> =>
      useNicknameUpdate(),
    );
    await act(async (): Promise<void> => {
      expect(await result.current.execute('새닉네임')).toBe(true);
    });
    expect(mockSetPersonProperties).not.toHaveBeenCalled();
    expect(result.current.request.status).toBe('success');
  });
});

afterEach((): void => {
  setAccessToken(null);
  localStorage.clear();
  jest.restoreAllMocks();
});

describe('피드 요청 이벤트와 인증 상태', (): void => {
  it('비로그인 조회는 결과와 관계없이 요청 이벤트를 한 번만 기록한다', async (): Promise<void> => {
    const { result } = renderHook((): ReturnType<typeof useFeedList> =>
      useFeedList({ categoryId: 2, sort: 'POPULAR' }),
    );
    await waitFor((): void => {
      expect(result.current.request.status).toBe('success');
    });

    expect(mockCapture).toHaveBeenCalledTimes(1);
    expect(mockCapture).toHaveBeenNthCalledWith(1, 'api_requested', {
      feature: 'feed',
      operation: 'feed_list',
      endpoint: '/feeds',
      method: 'GET',
      category_id: 2,
      sort: 'POPULAR',
      request_reason: 'first_page',
      auth_status: 'guest',
    });
  });

  it('토큰 복구 전의 로그인 이력은 guest 대신 unknown으로 기록한다', async (): Promise<void> => {
    localStorage.setItem('harudle.has-completed-oauth', 'true');
    const analytics = renderHook((): ReturnType<typeof useAnalytics> =>
      useAnalytics(),
    );
    mockGetFeedList.mockImplementationOnce(
      async (): Promise<FeedListResponse> => {
        setAccessToken('restored-token');
        analytics.result.current.confirmAuthenticatedSession();
        return emptyResponse;
      },
    );
    const { result, rerender } = renderHook<
      ReturnType<typeof useFeedList>,
      { categoryId: number | undefined }
    >(
      ({
        categoryId,
      }: {
        categoryId: number | undefined;
      }): ReturnType<typeof useFeedList> =>
        useFeedList({ categoryId, sort: 'LATEST' }),
      { initialProps: { categoryId: undefined } },
    );
    await waitFor((): void => {
      expect(result.current.request.status).toBe('success');
    });

    expect(mockCapture).toHaveBeenCalledTimes(1);
    expect(mockCapture.mock.calls[0]).toEqual([
      'api_requested',
      expect.objectContaining({ auth_status: 'unknown' }),
    ]);
    rerender({ categoryId: 1 });
    await waitFor((): void => {
      expect(result.current.request.status).toBe('success');
    });
    expect(mockCapture).toHaveBeenCalledTimes(2);
    expect(mockCapture.mock.calls[1]).toEqual([
      'api_requested',
      expect.objectContaining({ auth_status: 'logged_in' }),
    ]);
  });

  it('요청 중 토큰이 비워졌다 복구되어도 조회와 요청 이벤트를 다시 실행하지 않는다', async (): Promise<void> => {
    localStorage.setItem('harudle.has-completed-oauth', 'true');
    const analytics = renderHook((): ReturnType<typeof useAnalytics> =>
      useAnalytics(),
    );
    act((): void => analytics.result.current.confirmAuthenticatedSession());
    setAccessToken('expired-token');
    let resolveRequest: ((response: FeedListResponse) => void) | undefined;
    mockGetFeedList.mockImplementationOnce(
      (): Promise<FeedListResponse> =>
        new Promise((resolve): void => {
          resolveRequest = resolve;
        }),
    );
    const { result, rerender } = renderHook(
      (): ReturnType<typeof useFeedList> => useFeedList({ sort: 'LATEST' }),
    );
    setAccessToken(null);
    rerender();
    setAccessToken('refreshed-token');
    await act(async (): Promise<void> => {
      resolveRequest?.(emptyResponse);
    });

    expect(result.current.request.status).toBe('success');
    expect(mockGetFeedList).toHaveBeenCalledTimes(1);
    expect(mockCapture).toHaveBeenCalledTimes(1);
    expect(
      mockCapture.mock.calls.map(
        ([, properties]): unknown => properties?.auth_status,
      ),
    ).toEqual(['logged_in']);
  });

  it('실패한 조회에도 요청 이벤트만 한 번 기록한다', async (): Promise<void> => {
    mockGetFeedList.mockRejectedValueOnce(new Error('조회 실패'));
    const { result } = renderHook((): ReturnType<typeof useFeedList> =>
      useFeedList({ sort: 'LATEST' }),
    );
    await waitFor((): void => {
      expect(result.current.request.status).toBe('error');
    });
    expect(mockCapture).toHaveBeenCalledTimes(1);
    expect(mockCapture.mock.calls[0][0]).toBe('api_requested');
  });

  it('필터 변경 후의 첫 페이지 요청은 현재 필터 값을 기록하고 이전 이벤트를 바꾸지 않는다', async (): Promise<void> => {
    const { result, rerender } = renderHook(
      ({
        categoryId,
        sort,
      }: {
        categoryId: number;
        sort: 'LATEST' | 'POPULAR';
      }): ReturnType<typeof useFeedList> => useFeedList({ categoryId, sort }),
      { initialProps: { categoryId: 1, sort: 'LATEST' } },
    );
    await waitFor((): void => {
      expect(result.current.request.status).toBe('success');
    });
    rerender({ categoryId: 2, sort: 'LATEST' });
    await waitFor((): void => {
      expect(result.current.request.status).toBe('success');
    });
    rerender({ categoryId: 2, sort: 'POPULAR' });
    await waitFor((): void => {
      expect(result.current.request.status).toBe('success');
    });

    const requests = mockCapture.mock.calls.filter(
      ([event]): boolean => event === 'api_requested',
    );
    expect(
      requests.map(([, properties]): unknown => properties?.request_reason),
    ).toEqual(['first_page', 'first_page', 'first_page']);
    expect(
      requests.map(([, properties]): unknown => properties?.category_id),
    ).toEqual([1, 2, 2]);
    expect(requests.map(([, properties]): unknown => properties?.sort)).toEqual(
      ['LATEST', 'LATEST', 'POPULAR'],
    );
  });

  it('수집이 비활성화된 환경에서는 이벤트를 보내지 않는다', (): void => {
    mockIsEnabled = false;
    const { result } = renderHook((): ReturnType<typeof useAnalytics> =>
      useAnalytics(),
    );
    act((): void => {
      result.current.track('landing_trial_diary_create_clicked');
    });
    expect(mockCapture).not.toHaveBeenCalled();
  });

  it('로그인 성공 시 세션을 확인하고 로그아웃 후에는 이전 확인 상태를 재사용하지 않는다', async (): Promise<void> => {
    localStorage.setItem('harudle.has-completed-oauth', 'true');
    const { result } = renderHook((): ReturnType<typeof useAnalytics> =>
      useAnalytics(),
    );
    await act(async (): Promise<void> => {
      await result.current.identifyCurrentUser('access-token');
    });
    act((): void => result.current.track('landing_trial_diary_create_clicked'));
    expect(mockCapture).toHaveBeenLastCalledWith(
      'landing_trial_diary_create_clicked',
      { auth_status: 'logged_in' },
    );
    expect(mockIdentifyCurrentUser).toHaveBeenCalledTimes(1);

    act((): void => result.current.resetUser());
    localStorage.removeItem('harudle.has-completed-oauth');
    act((): void => result.current.track('landing_trial_diary_create_clicked'));
    expect(mockCapture).toHaveBeenLastCalledWith(
      'landing_trial_diary_create_clicked',
      { auth_status: 'guest' },
    );
    expect(mockReset).toHaveBeenCalledTimes(1);

    localStorage.setItem('harudle.has-completed-oauth', 'true');
    act((): void => result.current.track('landing_trial_diary_create_clicked'));
    expect(mockCapture).toHaveBeenLastCalledWith(
      'landing_trial_diary_create_clicked',
      { auth_status: 'unknown' },
    );
  });

  it('세션 만료로 인증 표시가 사라지면 guest로 기록하고 확인 상태를 비운다', (): void => {
    localStorage.setItem('harudle.has-completed-oauth', 'true');
    const { result } = renderHook((): ReturnType<typeof useAnalytics> =>
      useAnalytics(),
    );
    act((): void => result.current.confirmAuthenticatedSession());
    localStorage.removeItem('harudle.has-completed-oauth');
    act((): void => result.current.track('landing_trial_diary_create_clicked'));
    expect(mockCapture).toHaveBeenLastCalledWith(
      'landing_trial_diary_create_clicked',
      { auth_status: 'guest' },
    );
    localStorage.setItem('harudle.has-completed-oauth', 'true');
    act((): void => result.current.track('landing_trial_diary_create_clicked'));
    expect(mockCapture).toHaveBeenLastCalledWith(
      'landing_trial_diary_create_clicked',
      { auth_status: 'unknown' },
    );
  });
});
