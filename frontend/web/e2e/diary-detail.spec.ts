import { expect, test, type Page } from '@playwright/test';
import {
  MOCK_SCENARIO_HEADER,
  MOCK_SCENARIOS,
} from '../src/mocks/mockScenarios';
import { AUTHENTICATED_STORAGE_STATE } from './auth';

test.use({ storageState: AUTHENTICATED_STORAGE_STATE });

const TODAY = new Date('2026-08-30T12:00:00+09:00');
const SAMPLE_DIARY_ID = '00000000-0000-4000-8000-000000000001';
const SAMPLE_DIARY_URL = `/diary/${SAMPLE_DIARY_ID}`;
const SAMPLE_DIARY_TITLE = '비가 와도, 나는 괜찮았다.';
const SAMPLE_DIARY_DATE = '2026-08-12';
const SAMPLE_DIARY_STORY = '오늘 친구와 카페에 가서 오래 이야기했다.';

const setMockScenario = async (
  page: Page,
  scenario?: (typeof MOCK_SCENARIOS)[keyof typeof MOCK_SCENARIOS],
) => {
  await page.setExtraHTTPHeaders(
    scenario ? { [MOCK_SCENARIO_HEADER]: scenario } : {},
  );
};

const goToHome = async (page: Page) => {
  await page.clock.setFixedTime(TODAY);
  await page.goto('/');
};

const clickSampleDiary = async (page: Page) => {
  await page
    .getByRole('button', { name: new RegExp(SAMPLE_DIARY_TITLE) })
    .click();
  await expect(page).toHaveURL(SAMPLE_DIARY_URL);
};

const expectSampleDiaryDetail = async (page: Page) => {
  await expect(
    page.getByText(SAMPLE_DIARY_TITLE, { exact: true }),
  ).toBeVisible();
  await expect(page.getByText(SAMPLE_DIARY_DATE)).toBeVisible();
  await expect(page.getByText(SAMPLE_DIARY_STORY)).toBeVisible();
  await expect(page.getByRole('img', { name: '그림 일기' })).toBeVisible();
};

const goToSampleDiaryDetail = async (
  page: Page,
  scenario?: (typeof MOCK_SCENARIOS)[keyof typeof MOCK_SCENARIOS],
) => {
  await page.clock.setFixedTime(TODAY);
  await setMockScenario(page, scenario);
  await page.goto(SAMPLE_DIARY_URL);
  await expectSampleDiaryDetail(page);
};

test.describe('일기 상세', () => {
  test('홈에서 일기를 클릭하면 로딩 후 상세 정보를 확인할 수 있다', async ({
    page,
  }) => {
    await goToHome(page);
    await clickSampleDiary(page);
    const loadingSpinner = page.getByRole('img', { name: '로딩 중' });

    await expect(loadingSpinner).toBeVisible();
    await expectSampleDiaryDetail(page);
    await expect(loadingSpinner).toBeHidden();
  });

  test('상세 정보 조회에 실패하면 다시 불러올 수 있다', async ({ page }) => {
    await setMockScenario(page, MOCK_SCENARIOS.diaryDetailFailure);
    await goToHome(page);
    await clickSampleDiary(page);

    const errorPage = page.getByRole('alert');
    await expect(errorPage.getByText('일기를 불러오지 못했어요')).toBeVisible();
    await expect(
      errorPage.getByText(
        '일기 상세 정보를 불러오지 못했습니다. 다시 시도해주세요.',
      ),
    ).toBeVisible();

    await setMockScenario(page);
    await page.getByRole('button', { name: '다시 불러오기' }).click();

    await expectSampleDiaryDetail(page);
  });

  test('상단 뒤로가기 버튼을 누르면 홈 화면으로 이동한다', async ({ page }) => {
    await goToSampleDiaryDetail(page);

    await page.getByRole('button', { name: '뒤로 가기' }).click();

    await expect(page).toHaveURL('/');
    await expect(page.getByLabel('조회할 월')).toBeVisible();
  });

  test('삭제를 확인하면 일기를 삭제하고 홈 화면으로 이동한다', async ({
    page,
  }) => {
    await goToSampleDiaryDetail(page);

    const confirmPromise = page.waitForEvent('dialog');
    const deleteClickPromise = page
      .getByRole('button', { name: '더보기' })
      .click();
    const confirmDialog = await confirmPromise;

    expect(confirmDialog.type()).toBe('confirm');
    expect(confirmDialog.message()).toBe('일기를 삭제할까요?');
    await confirmDialog.accept();
    await deleteClickPromise;

    await expect(page).toHaveURL('/');
    await expect(page.getByText('5개의 기록')).toBeVisible();
    await expect(
      page.getByText(SAMPLE_DIARY_TITLE, { exact: true }),
    ).toHaveCount(0);
  });

  test('일기 삭제에 실패하면 에러 메시지를 보여준다', async ({ page }) => {
    await goToSampleDiaryDetail(page, MOCK_SCENARIOS.diaryDeleteFailure);

    const confirmPromise = page.waitForEvent('dialog');
    const deleteClickPromise = page
      .getByRole('button', { name: '더보기' })
      .click();
    const confirmDialog = await confirmPromise;
    const errorPromise = page.waitForEvent('dialog');
    await confirmDialog.accept();
    await deleteClickPromise;

    const errorDialog = await errorPromise;
    expect(errorDialog.message()).toBe(
      '일기 삭제에 실패했습니다. 다시 시도해주세요.',
    );
    await errorDialog.accept();

    await expect(page).toHaveURL(SAMPLE_DIARY_URL);
    await expect(
      page.getByText(SAMPLE_DIARY_TITLE, { exact: true }),
    ).toBeVisible();
  });

  test('공유하기 버튼을 누르면 브라우저 공유 UI를 요청한다', async ({
    page,
  }) => {
    await goToSampleDiaryDetail(page);
    await page.evaluate(() => {
      Object.defineProperty(navigator, 'share', {
        configurable: true,
        value: async (data: unknown) => {
          sessionStorage.setItem('sharedDiary', JSON.stringify(data));
        },
      });
    });

    await page.getByRole('button', { name: '공유하기' }).click();

    await expect
      .poll(() => page.evaluate(() => sessionStorage.getItem('sharedDiary')))
      .not.toBeNull();
    const sharedDiary = await page.evaluate(() =>
      JSON.parse(sessionStorage.getItem('sharedDiary') ?? '{}'),
    );
    expect(sharedDiary).toMatchObject({
      title: SAMPLE_DIARY_TITLE,
      url: expect.stringContaining('/shares/'),
    });
  });

  test('일기 공유에 실패하면 에러 메시지를 보여준다', async ({ page }) => {
    await goToSampleDiaryDetail(page, MOCK_SCENARIOS.diaryShareFailure);

    const errorPromise = page.waitForEvent('dialog');
    const shareClickPromise = page
      .getByRole('button', { name: '공유하기' })
      .click();
    const errorDialog = await errorPromise;

    expect(errorDialog.message()).toBe(
      '공유 링크를 만들지 못했습니다. 다시 시도해주세요.',
    );
    await errorDialog.accept();
    await shareClickPromise;
  });

  test('이미지 저장 버튼을 누르면 일기 이미지를 저장한다', async ({ page }) => {
    await goToSampleDiaryDetail(page);

    const downloadPromise = page.waitForEvent('download');
    await page.getByRole('button', { name: '이미지 저장' }).click();
    const download = await downloadPromise;

    expect(download.suggestedFilename()).toBe('harudle-diary.png');
  });

  test('이미지 저장에 실패하면 에러 메시지를 보여준다', async ({ page }) => {
    await goToSampleDiaryDetail(page);
    const diaryImageUrl = await page
      .getByRole('img', { name: '그림 일기' })
      .evaluate((image) => image.src);

    await page.evaluate((failedImageUrl) => {
      const originalFetch = window.fetch.bind(window);
      window.fetch = async (input, init) => {
        const requestUrl =
          input instanceof Request
            ? input.url
            : new URL(input, location.href).href;

        if (requestUrl === failedImageUrl) {
          return new Response(null, { status: 503 });
        }

        return originalFetch(input, init);
      };
    }, diaryImageUrl);

    const errorPromise = page.waitForEvent('dialog');
    const downloadClickPromise = page
      .getByRole('button', { name: '이미지 저장' })
      .click();
    const errorDialog = await errorPromise;

    expect(errorDialog.message()).toBe('이미지 저장에 실패했습니다.');
    await errorDialog.accept();
    await downloadClickPromise;
  });
});
