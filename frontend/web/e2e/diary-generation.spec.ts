import { DIARY_WRITE_COPY } from '../src/pages/diary-write/copy';
import { ALBUM_COPY } from '../src/pages/album/copy';
import { FEED_COPY } from '../src/pages/feed/copy';
import { DIARY_GENERATION_FAILURE_MESSAGES } from '../src/shared/errorMessage';
import {
  DIARY_CONTENT_SESSION_KEY,
  DIARY_GENERATION_ID_KEY,
  DIARY_GENERATION_TOAST_ID_KEY,
} from '../src/shared/constants';
import {
  expect,
  test,
  type Locator,
  type Page,
  type Request,
} from '@playwright/test';
import {
  MOCK_SCENARIO_HEADER,
  MOCK_SCENARIOS,
} from '../src/mocks/mockScenarios';
import { AUTHENTICATED_STORAGE_STATE } from './auth';

test.use({ storageState: AUTHENTICATED_STORAGE_STATE });

const TODAY = new Date('2026-08-30T12:00:00+09:00');
const VALID_DIARY_CONTENT =
  '오늘은 친구와 공원을 산책하며 즐거운 이야기를 나누었다.';
const NEXT_DIARY_CONTENT =
  '두 번째 일기는 가족과 저녁을 먹으며 나눈 이야기를 기록했다.';
const GENERATED_DIARY_TITLE = '오늘 하루의 소중한 기록';
const LOADING_MESSAGE = '네컷만화를 만들고 있어요';
const SUCCESS_MESSAGE = '네컷만화가 완성됐어요!';
const REQUEST_ERROR_MESSAGE = '네컷만화 생성 요청을 접수하지 못했어요.';
const [GENERATION_ERROR_MESSAGE] =
  DIARY_GENERATION_FAILURE_MESSAGES.AI_PROVIDER_ERROR;

interface DiaryScenarioOptions {
  readonly scenario?: (typeof MOCK_SCENARIOS)[keyof typeof MOCK_SCENARIOS];
  readonly previousUrl?: string;
}

interface SavedGenerationState {
  readonly generationId: string | null;
  readonly toastId: string | null;
  readonly draft: string | null;
}

const readGenerationState = (page: Page): Promise<SavedGenerationState> => {
  return page.evaluate(
    ([generationKey, toastKey, contentKey]): SavedGenerationState => ({
      generationId: localStorage.getItem(generationKey),
      toastId: localStorage.getItem(toastKey),
      draft: sessionStorage.getItem(contentKey),
    }),
    [
      DIARY_GENERATION_ID_KEY,
      DIARY_GENERATION_TOAST_ID_KEY,
      DIARY_CONTENT_SESSION_KEY,
    ],
  );
};

const startNextGeneration = async (page: Page): Promise<void> => {
  await page.setExtraHTTPHeaders({});
  await page
    .locator('main button')
    .filter({ has: page.locator('img') })
    .last()
    .click();
  await expect(page).toHaveURL('/diary-write');
  await submitDiary(page, NEXT_DIARY_CONTENT);
  await expect(page).toHaveURL('/album');
  await expect(page.getByText(LOADING_MESSAGE, { exact: true })).toBeVisible();
};

const goToDiaryWritePage = async (
  page: Page,
  { scenario, previousUrl = '/album' }: DiaryScenarioOptions = {},
): Promise<void> => {
  await page.clock.install({ time: TODAY });
  if (scenario) {
    await page.setExtraHTTPHeaders({ [MOCK_SCENARIO_HEADER]: scenario });
  }
  await page.goto(previousUrl);
  // 실제 화면 이동으로 이전 페이지를 브라우저 기록에 남긴다.
  if (previousUrl.startsWith('/album')) {
    await page
      .locator('main button')
      .filter({ has: page.locator('img') })
      .last()
      .click();
  } else {
    await page.getByRole('button', { name: FEED_COPY.createAction }).click();
  }
  await expect(page).toHaveURL('/diary-write');
};

const submitDiary = async (
  page: Page,
  content = VALID_DIARY_CONTENT,
): Promise<void> => {
  await page.getByPlaceholder(DIARY_WRITE_COPY.placeholder).fill(content);
  await page.locator('form').getByRole('button').click();
};

const startGeneration = async (
  page: Page,
  options: DiaryScenarioOptions = {},
): Promise<Locator> => {
  await goToDiaryWritePage(page, options);
  await submitDiary(page);
  await expect(page).toHaveURL(options.previousUrl ?? '/album');
  const generationId = await page.evaluate(
    (key): string | null => localStorage.getItem(key),
    DIARY_GENERATION_ID_KEY,
  );
  if (!generationId) throw new Error('생성 작업 ID가 저장되지 않았습니다.');
  const toast = getToasts(page);
  await expect(toast).toContainText(LOADING_MESSAGE);
  return toast;
};

const getToasts = (page: Page): Locator => {
  return page.locator('div:has(> span[data-type])');
};

const isGenerationStatusUrl = (url: string): boolean => {
  return new URL(url).pathname.startsWith('/api/v1/diary-generations/');
};

const collectStatusRequests = (page: Page): Request[] => {
  const requests: Request[] = [];
  page.on('request', (request): void => {
    if (request.method() === 'GET' && isGenerationStatusUrl(request.url())) {
      requests.push(request);
    }
  });
  return requests;
};

const advanceToPoll = async (
  page: Page,
  milliseconds: number,
  status = 200,
): Promise<void> => {
  const response = page.waitForResponse((response): boolean => {
    return (
      isGenerationStatusUrl(response.url()) && response.status() === status
    );
  });
  await page.clock.fastForward(milliseconds);
  await response;
};

const expectPollingStopped = async (
  page: Page,
  requests: Request[],
): Promise<void> => {
  expect(requests.length).toBeGreaterThan(0);
  const count = requests.length;
  await page.clock.fastForward(15_000);
  expect(requests).toHaveLength(count);
};

const expectRemainingCount = async (
  page: Page,
  count: number,
): Promise<void> => {
  await expect(
    page.getByText(
      `${ALBUM_COPY.remainingUsage.before}${count}${ALBUM_COPY.remainingUsage.after}`,
    ),
  ).toBeVisible();
};

const expectGenerationCleared = async (page: Page): Promise<void> => {
  const ids = await page.evaluate(
    ([generationKey, toastKey]): (string | null)[] => {
      return [
        localStorage.getItem(generationKey),
        localStorage.getItem(toastKey),
      ];
    },
    [DIARY_GENERATION_ID_KEY, DIARY_GENERATION_TOAST_ID_KEY],
  );
  expect(ids).toEqual([null, null]);
};

test.describe('일기 생성', (): void => {
  for (const { name, previousUrl } of [
    { name: '피드', previousUrl: '/?categoryId=1&sort=POPULAR' },
    { name: '네컷 모아보기', previousUrl: '/album?from=previous' },
  ]) {
    test(`${name}에서 생성 요청 접수에 성공하면 원래 화면으로 돌아가 로딩 토스트 하나를 표시한다`, async ({
      page,
    }): Promise<void> => {
      await startGeneration(page, { previousUrl });
      await expect(page).toHaveURL(previousUrl);
      await expect(getToasts(page)).toHaveCount(1);
    });
  }

  test('생성 요청 접수에 실패하면 작성 내용과 화면을 유지하고 오류 토스트를 표시한다', async ({
    page,
  }): Promise<void> => {
    const requests = collectStatusRequests(page);
    await goToDiaryWritePage(page, {
      scenario: MOCK_SCENARIOS.diaryGenerationRequestFailure,
    });
    await submitDiary(page);
    await expect(page.getByText(REQUEST_ERROR_MESSAGE)).toBeVisible();
    await expect(page).toHaveURL('/diary-write');
    await expect(page.getByRole('textbox')).toHaveValue(VALID_DIARY_CONTENT);
    await expect(page.getByText(LOADING_MESSAGE)).toHaveCount(0);
    await expect(getToasts(page)).toHaveCount(1);
    await expectGenerationCleared(page);
    await page.clock.fastForward(10_000);
    expect(requests).toHaveLength(0);
  });

  test('생성 작업이 실패하면 같은 로딩 토스트를 실패로 갱신하고 폴링을 멈춘다', async ({
    page,
  }): Promise<void> => {
    const requests = collectStatusRequests(page);
    const toast = await startGeneration(page, {
      scenario: MOCK_SCENARIOS.diaryGenerationFailure,
    });
    const loadingToast = await toast.elementHandle();
    if (!loadingToast) throw new Error('로딩 토스트가 없습니다.');
    await advanceToPoll(page, 5_000);
    await expect(toast).toContainText(LOADING_MESSAGE);
    await advanceToPoll(page, 5_000);
    await expect(toast).toContainText(GENERATION_ERROR_MESSAGE);
    await expect(page.getByText(LOADING_MESSAGE)).toHaveCount(0);
    await expect(getToasts(page)).toHaveCount(1);
    expect(
      await loadingToast.evaluate((node): boolean => node.isConnected),
    ).toBe(true);
    await expectGenerationCleared(page);
    await expectPollingStopped(page, requests);
  });

  test('실패 토스트의 다시 작성하기를 누르면 토스트가 사라지고 이전 내용이 남는다', async ({
    page,
  }): Promise<void> => {
    const toast = await startGeneration(page, {
      scenario: MOCK_SCENARIOS.diaryGenerationFailure,
    });
    await advanceToPoll(page, 10_000);
    await expect(toast).toContainText(GENERATION_ERROR_MESSAGE);
    await toast.getByRole('button', { name: '다시 작성하기' }).click();
    await expect(page).toHaveURL('/diary-write');
    await expect(getToasts(page)).toHaveCount(0);
    await expect(page.getByRole('textbox')).toHaveValue(VALID_DIARY_CONTENT);
  });

  test('생성 작업을 찾을 수 없으면 같은 토스트를 오류로 갱신하고 폴링을 멈춘다', async ({
    page,
  }): Promise<void> => {
    const requests = collectStatusRequests(page);
    const toast = await startGeneration(page, {
      scenario: MOCK_SCENARIOS.diaryGenerationNotFound,
    });
    const loadingToast = await toast.elementHandle();
    if (!loadingToast) throw new Error('로딩 토스트가 없습니다.');
    await advanceToPoll(page, 5_000, 404);
    await expect(toast).toContainText('생성 작업을 찾을 수 없습니다.');
    await expect(toast).toContainText('다시 시도해주세요');
    await expect(toast.locator('[data-type]')).toHaveAttribute(
      'data-type',
      'error',
    );
    await expect(getToasts(page)).toHaveCount(1);
    await expect(page.getByText(LOADING_MESSAGE)).toHaveCount(0);
    expect(
      await loadingToast.evaluate((node): boolean => node.isConnected),
    ).toBe(true);
    await expectGenerationCleared(page);
    await expectPollingStopped(page, requests);
    await toast.getByRole('button', { name: '다시 작성하기' }).click();
    await expect(page).toHaveURL('/diary-write');
    await expect(getToasts(page)).toHaveCount(0);
    await expect(page.getByRole('textbox')).toHaveValue(VALID_DIARY_CONTENT);
  });

  test('성공 토스트의 보러가기를 누르면 생성된 만화를 확인하고 토스트가 사라진다', async ({
    page,
  }): Promise<void> => {
    const requests = collectStatusRequests(page);
    const toast = await startGeneration(page);
    await advanceToPoll(page, 10_000);
    await expect(toast).toContainText(SUCCESS_MESSAGE);
    await expect(getToasts(page)).toHaveCount(1);
    await expectGenerationCleared(page);
    await expectPollingStopped(page, requests);
    await toast.getByRole('button', { name: '보러가기' }).click();
    await expect(page).toHaveURL(/\/diary\/[0-9a-f-]+$/);
    await expect(getToasts(page)).toHaveCount(0);
    await expect(page.getByText(GENERATED_DIARY_TITLE)).toBeVisible();
    await expect(page.getByText('2026-08-30')).toBeVisible();
    await expect(page.getByRole('img', { name: '네컷만화' })).toBeVisible();
    await expect(page.getByText(VALID_DIARY_CONTENT)).toBeVisible();
    const draft = await page.evaluate(
      (key): string | null => sessionStorage.getItem(key),
      DIARY_CONTENT_SESSION_KEY,
    );
    expect(draft).toBeNull();
  });

  for (const action of ['보러가기', '×']) {
    test(`진행 토스트를 닫아도 완료 알림을 다시 표시하고 ${action} 버튼으로 닫을 수 있다`, async ({
      page,
    }): Promise<void> => {
      const requests = collectStatusRequests(page);
      const toast = await startGeneration(page);
      const generationId = await page.evaluate(
        (key): string | null => localStorage.getItem(key),
        DIARY_GENERATION_ID_KEY,
      );

      await toast.getByRole('button', { name: '×', exact: true }).click();
      await expect(getToasts(page)).toHaveCount(0);
      const savedIds = await page.evaluate(
        ([generationKey, toastKey]): (string | null)[] => [
          localStorage.getItem(generationKey),
          localStorage.getItem(toastKey),
        ],
        [DIARY_GENERATION_ID_KEY, DIARY_GENERATION_TOAST_ID_KEY],
      );
      expect(savedIds).toEqual([generationId, null]);
      await expect(page.getByTestId('diary-generation-skeleton')).toBeVisible();

      await advanceToPoll(page, 5_000);
      await expect(getToasts(page)).toHaveCount(0);
      await advanceToPoll(page, 5_000);
      await expect(toast).toContainText(SUCCESS_MESSAGE);
      await expect(getToasts(page)).toHaveCount(1);
      await expectGenerationCleared(page);
      await expectPollingStopped(page, requests);

      await toast.getByRole('button', { name: action, exact: true }).click();
      await expect(getToasts(page)).toHaveCount(0);
      if (action === '보러가기') {
        await expect(page).toHaveURL(/\/diary\/[0-9a-f-]+$/);
        await expect(page.getByText(GENERATED_DIARY_TITLE)).toBeVisible();
        await expect(page.getByText(VALID_DIARY_CONTENT)).toBeVisible();
      } else {
        await expect(page).toHaveURL('/album');
      }
    });
  }

  for (const { name, scenario, message, pollAfterMs, responseStatus } of [
    {
      name: '생성 실패',
      scenario: MOCK_SCENARIOS.diaryGenerationFailure,
      message: GENERATION_ERROR_MESSAGE,
      pollAfterMs: 10_000,
      responseStatus: 200,
    },
    {
      name: '생성 작업 조회 불가',
      scenario: MOCK_SCENARIOS.diaryGenerationNotFound,
      message: '생성 작업을 찾을 수 없습니다.',
      pollAfterMs: 5_000,
      responseStatus: 404,
    },
  ]) {
    for (const action of ['다시 작성하기', '×']) {
      test(`진행 토스트를 닫은 뒤 ${name} 시 오류 알림을 다시 표시하고 ${action}가 동작한다`, async ({
        page,
      }): Promise<void> => {
        const requests = collectStatusRequests(page);
        const toast = await startGeneration(page, { scenario });
        await toast.getByRole('button', { name: '×', exact: true }).click();
        await expect(getToasts(page)).toHaveCount(0);

        await advanceToPoll(page, pollAfterMs, responseStatus);
        await expect(toast).toContainText(message);
        await expect(toast.locator('[data-type]')).toHaveAttribute(
          'data-type',
          'error',
        );
        await expect(getToasts(page)).toHaveCount(1);
        await expectGenerationCleared(page);
        await expectPollingStopped(page, requests);
        const draft = await page.evaluate(
          (key): string | null => sessionStorage.getItem(key),
          DIARY_CONTENT_SESSION_KEY,
        );
        expect(draft).toBe(VALID_DIARY_CONTENT);

        await toast.getByRole('button', { name: action, exact: true }).click();
        await expect(getToasts(page)).toHaveCount(0);
        if (action === '다시 작성하기') {
          await expect(page).toHaveURL('/diary-write');
          await expect(page.getByRole('textbox')).toHaveValue(
            VALID_DIARY_CONTENT,
          );
        } else {
          await expect(page).toHaveURL('/album');
        }
      });
    }
  }

  test('성공 응답 뒤에 이전 미완료 응답이 도착해도 성공 토스트와 폴링 종료 상태를 유지한다', async ({
    page,
  }): Promise<void> => {
    const requests = collectStatusRequests(page);
    const toast = await startGeneration(page, {
      scenario: MOCK_SCENARIOS.diaryGenerationOutOfOrder,
    });
    const firstRequestPromise = page.waitForRequest((request): boolean => {
      return request.method() === 'GET' && isGenerationStatusUrl(request.url());
    });
    await page.clock.fastForward(5_000);
    const firstRequest = await firstRequestPromise;
    const delayedResponse = page.waitForResponse((response): boolean => {
      return response.request() === firstRequest;
    });

    await advanceToPoll(page, 5_000);
    await expect(toast).toContainText(SUCCESS_MESSAGE);
    expect(requests).toHaveLength(2);
    await expectGenerationCleared(page);

    await page.clock.fastForward(10_000);
    await expect((await delayedResponse).json()).resolves.toMatchObject({
      status: 'PROCESSING',
    });
    await expectPollingStopped(page, requests);
    await expect(toast).toContainText(SUCCESS_MESSAGE);
    await expect(page.getByText(LOADING_MESSAGE)).toHaveCount(0);
    await expect(getToasts(page)).toHaveCount(1);
    await expectGenerationCleared(page);
  });

  for (const { name, scenario, initialScenario, completionMessage, status } of [
    {
      name: '성공',
      scenario: MOCK_SCENARIOS.diaryGenerationDelayedSuccess,
      initialScenario: undefined,
      completionMessage: SUCCESS_MESSAGE,
      status: 200,
    },
    {
      name: '실패',
      scenario: MOCK_SCENARIOS.diaryGenerationDelayedFailure,
      initialScenario: MOCK_SCENARIOS.diaryGenerationFailure,
      completionMessage: GENERATION_ERROR_MESSAGE,
      status: 200,
    },
    {
      name: '조회 오류',
      scenario: MOCK_SCENARIOS.diaryGenerationDelayedNotFound,
      initialScenario: undefined,
      completionMessage: SUCCESS_MESSAGE,
      status: 404,
    },
  ]) {
    test(`이전 작업의 늦은 ${name} 응답이 새 작업의 토스트와 저장 상태를 건드리지 않는다`, async ({
      page,
    }): Promise<void> => {
      await startGeneration(page, { scenario: initialScenario });
      const previousState = await readGenerationState(page);
      await page.setExtraHTTPHeaders({ [MOCK_SCENARIO_HEADER]: scenario });

      const firstRequestPromise = page.waitForRequest((request): boolean => {
        return (
          request.method() === 'GET' && isGenerationStatusUrl(request.url())
        );
      });
      await page.clock.fastForward(5_000);
      const firstRequest = await firstRequestPromise;
      const delayedResponse = page.waitForResponse((response): boolean => {
        return response.request() === firstRequest;
      });
      // MSW의 공통 응답 지연(150ms)을 지나 첫 조회의 추가 지연을 시작한다.
      await page.clock.runFor(500);

      // 첫 조회는 대기시킨 채 두 번째 조회로 이전 작업을 완료한다.
      await advanceToPoll(page, 5_000);
      await expect(
        page.getByText(completionMessage, { exact: true }),
      ).toBeVisible();
      await expectGenerationCleared(page);

      await startNextGeneration(page);
      const nextState = await readGenerationState(page);
      expect(nextState.generationId).not.toBeNull();
      expect(nextState.generationId).not.toBe(previousState.generationId);
      expect(nextState.toastId).not.toBeNull();
      expect(nextState.toastId).not.toBe(previousState.toastId);
      expect(nextState.draft).toBe(NEXT_DIARY_CONTENT);
      await expect(getToasts(page)).toHaveCount(2);

      // 네트워크 지연을 기다리는 동안 새 작업의 생성 시간이 흐르지 않게 한다.
      await page.clock.pauseAt(await page.evaluate((): number => Date.now()));
      const nextResponse = page.waitForResponse((response): boolean => {
        return (
          new URL(response.url()).pathname ===
          `/api/v1/diary-generations/${nextState.generationId}`
        );
      });
      await page.clock.fastForward(5_000);
      await page.clock.runFor(500);
      const response = await delayedResponse;
      expect(response.status()).toBe(status);
      await response.finished();
      await expect((await nextResponse).json()).resolves.toMatchObject({
        status: 'PROCESSING',
      });

      // 늦은 응답을 처리한 뒤에도 새 작업의 폴링이 계속되어야 한다.
      expect(await readGenerationState(page)).toEqual(nextState);
      await expect(
        page.getByText(LOADING_MESSAGE, { exact: true }),
      ).toBeVisible();
      await expect(
        page.getByText(completionMessage, { exact: true }),
      ).toHaveCount(1);
      await expect(getToasts(page)).toHaveCount(2);
      await expect(page.getByTestId('diary-generation-skeleton')).toBeVisible();

      await page.clock.resume();
      await advanceToPoll(page, 5_000);
      await expect(
        page.getByText(LOADING_MESSAGE, { exact: true }),
      ).toHaveCount(0);
      await expectGenerationCleared(page);
      expect((await readGenerationState(page)).draft).toBeNull();
      await expect(getToasts(page).last()).toContainText(SUCCESS_MESSAGE);
    });
  }

  test('이전 작업의 완료 토스트를 닫아도 새 작업의 토스트 ID와 작성 내용을 유지한다', async ({
    page,
  }): Promise<void> => {
    await startGeneration(page);
    await advanceToPoll(page, 10_000);
    const previousToast = getToasts(page).filter({ hasText: SUCCESS_MESSAGE });
    await expect(previousToast).toBeVisible();
    await expectGenerationCleared(page);

    await startNextGeneration(page);
    const nextState = await readGenerationState(page);
    await expect(getToasts(page)).toHaveCount(2);
    await previousToast.getByRole('button', { name: '×', exact: true }).click();

    await expect(getToasts(page)).toHaveCount(1);
    await expect(getToasts(page)).toContainText(LOADING_MESSAGE);
    expect(await readGenerationState(page)).toEqual(nextState);

    await advanceToPoll(page, 5_000);
    await expect(getToasts(page)).toContainText(LOADING_MESSAGE);
    expect(await readGenerationState(page)).toEqual(nextState);
    await advanceToPoll(page, 5_000);
    await expect(getToasts(page)).toContainText(SUCCESS_MESSAGE);
    await expectGenerationCleared(page);
    expect((await readGenerationState(page)).draft).toBeNull();
  });

  test('네컷 모아보기에서 생성이 완료되면 목록과 생성 횟수가 반영된다', async ({
    page,
  }): Promise<void> => {
    const toast = await startGeneration(page);
    await expect(page.getByTestId('diary-generation-skeleton')).toBeVisible();
    await expectRemainingCount(page, 2);
    const countLabel = page.getByText(/^총 \d+편$/);
    const countBefore = Number(
      (await countLabel.innerText()).replace(/\D/g, ''),
    );
    await advanceToPoll(page, 10_000);
    await expect(toast).toContainText(SUCCESS_MESSAGE);
    await expect(page.getByTestId('diary-generation-skeleton')).toBeHidden();
    await expect(page.getByText(GENERATED_DIARY_TITLE)).toBeVisible();
    await expect(countLabel).toHaveText(`총 ${countBefore + 1}편`);
    await expectRemainingCount(page, 2);
  });

  test('생성 중 새로고침해도 토스트 하나를 복원하고 동일 작업의 완료를 확인한다', async ({
    page,
  }): Promise<void> => {
    const toast = await startGeneration(page, {
      scenario: MOCK_SCENARIOS.diaryGenerationResume,
    });
    const id = await page.evaluate(
      (key): string | null => localStorage.getItem(key),
      DIARY_GENERATION_ID_KEY,
    );
    await page.reload();
    await expect(toast).toContainText(LOADING_MESSAGE);
    await expect(getToasts(page)).toHaveCount(1);
    const restoredId = await page.evaluate(
      (key): string | null => localStorage.getItem(key),
      DIARY_GENERATION_ID_KEY,
    );
    expect(restoredId).toBe(id);
    await advanceToPoll(page, 10_000);
    await expect(toast).toContainText(SUCCESS_MESSAGE);
    await expect(getToasts(page)).toHaveCount(1);
    await expectGenerationCleared(page);
    await toast.getByRole('button', { name: '보러가기' }).click();
    await expect(page.getByText(GENERATED_DIARY_TITLE)).toBeVisible();
    await expect(page.getByText(VALID_DIARY_CONTENT)).toBeVisible();
  });

  test('만화 생성 중 로그아웃하면 생성 기록을 삭제하고 폴링과 반복 새로고침을 멈춘다', async ({
    page,
  }): Promise<void> => {
    const requests = collectStatusRequests(page);
    const navigations: Request[] = [];
    page.on('request', (request): void => {
      if (
        request.isNavigationRequest() &&
        request.resourceType() === 'document'
      ) {
        navigations.push(request);
      }
    });

    const toast = await startGeneration(page);
    await advanceToPoll(page, 5_000);
    await expect(toast).toContainText(LOADING_MESSAGE);
    await page.getByRole('button', { name: '설정', exact: true }).click();
    await expect(page.getByText('kakao', { exact: true })).toBeVisible();
    // Mock 서버에는 세션이 없으므로 로그아웃 이후의 세션 만료를 명시한다.
    await page.setExtraHTTPHeaders({
      [MOCK_SCENARIO_HEADER]: MOCK_SCENARIOS.authRefreshFailure,
    });
    await page.getByRole('button', { name: '로그아웃', exact: true }).click();
    await expect(page).toHaveURL('/');
    await expect(page.getByRole('combobox')).toBeVisible();

    // 로그아웃 후 첫 폴링은 인증 오류로 정리되고 공개 피드를 다시 불러온다.
    const redirected = page.waitForEvent('load');
    await page.clock.fastForward(5_000);
    await redirected;
    await expect(page.getByRole('combobox')).toBeVisible();
    await expectGenerationCleared(page);
    await expect(page.getByText(LOADING_MESSAGE)).toHaveCount(0);

    const navigationCount = navigations.length;
    await expectPollingStopped(page, requests);
    expect(navigations).toHaveLength(navigationCount);
    await expect(page).toHaveURL('/');
    await expectGenerationCleared(page);
  });

  test('다른 화면으로 이동해도 같은 토스트를 유지하고 생성 결과를 표시한다', async ({
    page,
  }): Promise<void> => {
    const toast = await startGeneration(page);
    await page.getByRole('button', { name: '설정', exact: true }).click();
    await expect(page).toHaveURL('/setting');
    await expect(toast).toContainText(LOADING_MESSAGE);
    await expect(getToasts(page)).toHaveCount(1);
    await advanceToPoll(page, 10_000);
    await expect(page).toHaveURL('/setting');
    await expect(toast).toContainText(SUCCESS_MESSAGE);
    await page
      .getByRole('button', { name: '네컷 모아보기', exact: true })
      .click();
    await expect(page).toHaveURL('/album');
    await expect(page.getByText(GENERATED_DIARY_TITLE)).toBeVisible();
    await expectRemainingCount(page, 2);
  });

  test('일시적인 상태 조회 오류가 나도 작업을 유지하고 다음 폴링에서 회복한다', async ({
    page,
  }): Promise<void> => {
    const requests = collectStatusRequests(page);
    const toast = await startGeneration(page, {
      scenario: MOCK_SCENARIOS.diaryGenerationStatusFailureOnce,
    });
    await advanceToPoll(page, 5_000, 503);
    await expect(toast).toContainText(LOADING_MESSAGE);
    await expect(getToasts(page)).toHaveCount(1);
    const generationId = await page.evaluate(
      (key): string | null => localStorage.getItem(key),
      DIARY_GENERATION_ID_KEY,
    );
    expect(generationId).not.toBeNull();
    await advanceToPoll(page, 5_000);
    await expect(toast).toContainText(SUCCESS_MESSAGE);
    expect(requests).toHaveLength(2);
    await expectGenerationCleared(page);
    await expectPollingStopped(page, requests);
  });

  test('10자 미만의 일기는 제출할 수 없다', async ({ page }): Promise<void> => {
    await goToDiaryWritePage(page);
    await submitDiary(page, '123456789');
    await expect(page.getByText(DIARY_WRITE_COPY.minLengthError)).toBeVisible();
    await expect(page).toHaveURL('/diary-write');
    await expect(page.getByRole('textbox')).toHaveValue('123456789');
    await expect(getToasts(page)).toHaveCount(0);
  });

  test('생성 작업이 실패하면 앨범의 남은 생성 횟수를 복구한다', async ({
    page,
  }): Promise<void> => {
    const toast = await startGeneration(page, {
      scenario: MOCK_SCENARIOS.diaryGenerationFailure,
    });
    await expectRemainingCount(page, 2);
    await advanceToPoll(page, 10_000);
    await expect(toast).toContainText(GENERATION_ERROR_MESSAGE);
    await expect(page.getByTestId('diary-generation-skeleton')).toBeHidden();
    await expect(page.getByText(GENERATED_DIARY_TITLE)).toHaveCount(0);
    await expectRemainingCount(page, 3);
  });
});
