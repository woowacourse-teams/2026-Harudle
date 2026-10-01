import { DIARY_DETAIL_COPY } from '../src/pages/diary-detail/copy';
import { HOME_COPY } from '../src/pages/home/copy';
import { ERROR_MESSAGES } from '../src/shared/errorMessage';
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
  await expect(page.getByRole('img', { name: '네컷만화' })).toBeVisible();
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
    await expect(
      errorPage.getByText(DIARY_DETAIL_COPY.loadErrorTitle),
    ).toBeVisible();
    await expect(
      errorPage.getByText(
        '일기 상세 정보를 불러오지 못했습니다. 다시 시도해주세요.',
      ),
    ).toBeVisible();

    await setMockScenario(page);
    await page
      .getByRole('button', { name: DIARY_DETAIL_COPY.reloadAction })
      .click();

    await expectSampleDiaryDetail(page);
  });

  test('상단 뒤로가기 버튼을 누르면 홈 화면으로 이동한다', async ({ page }) => {
    await goToHome(page);
    await clickSampleDiary(page);

    await page.getByRole('button', { name: '뒤로 가기' }).click();

    await expect(page).toHaveURL('/');
    await expect(page.getByLabel('조회할 월')).toBeVisible();
  });

  test('다른 월에서 상세 화면에 진입한 뒤 돌아가면 선택한 월을 유지한다', async ({
    page,
  }) => {
    await page.clock.setFixedTime(new Date('2026-07-30T12:00:00+09:00'));
    await page.goto('/');

    const monthInput = page.getByLabel('조회할 월');
    await monthInput.fill('2026-08');
    await expect(page).toHaveURL('/?yearMonth=2026-08');

    await clickSampleDiary(page);
    await page.getByRole('button', { name: '뒤로 가기' }).click();

    await expect(page).toHaveURL('/?yearMonth=2026-08');
    await expect(monthInput).toHaveValue('2026-08');
  });

  test('삭제를 확인하면 일기를 삭제하고 홈 화면으로 이동한다', async ({
    page,
  }) => {
    await goToSampleDiaryDetail(page);

    const confirmPromise = page.waitForEvent('dialog');
    const deleteClickPromise = page
      .getByRole('button', { name: DIARY_DETAIL_COPY.deleteAction })
      .click();
    const confirmDialog = await confirmPromise;

    expect(confirmDialog.type()).toBe('confirm');
    expect(confirmDialog.message()).toBe(DIARY_DETAIL_COPY.deleteConfirm);
    await confirmDialog.accept();
    await deleteClickPromise;

    await expect(page).toHaveURL('/');
    await expect(
      page.getByText(
        `${HOME_COPY.monthlyCount.before}5${HOME_COPY.monthlyCount.after}`,
      ),
    ).toBeVisible();
    await expect(
      page.getByText(SAMPLE_DIARY_TITLE, { exact: true }),
    ).toHaveCount(0);
  });

  test('일기 삭제에 실패하면 에러 메시지를 보여준다', async ({ page }) => {
    await goToSampleDiaryDetail(page, MOCK_SCENARIOS.diaryDeleteFailure);

    const confirmPromise = page.waitForEvent('dialog');
    const deleteClickPromise = page
      .getByRole('button', { name: DIARY_DETAIL_COPY.deleteAction })
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

    await page
      .getByRole('button', { name: DIARY_DETAIL_COPY.shareAction })
      .click();

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
      .getByRole('button', { name: DIARY_DETAIL_COPY.shareAction })
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
    await page
      .getByRole('button', { name: DIARY_DETAIL_COPY.downloadAction })
      .click();
    const download = await downloadPromise;

    expect(download.suggestedFilename()).toBe(
      '하루들_2026-08-12_비가 와도, 나는 괜찮았다.png',
    );
  });

  for (const [label, scenario, character] of [
    ['한글', MOCK_SCENARIOS.diaryLongKoreanTitle, '가'],
    ['이모지', MOCK_SCENARIOS.diaryLongEmojiTitle, '😀'],
  ] as const) {
    test(`긴 ${label} 제목은 이미지 저장 시 20 code point로 제한한다`, async ({
      page,
    }): Promise<void> => {
      await setMockScenario(page, scenario);
      await page.goto(SAMPLE_DIARY_URL);
      await expect(
        page.getByText(character.repeat(100), { exact: true }),
      ).toBeVisible();

      const downloadPromise = page.waitForEvent('download');
      await page
        .getByRole('button', { name: DIARY_DETAIL_COPY.downloadAction })
        .click();
      const download = await downloadPromise;
      const fileName = download.suggestedFilename();

      expect(fileName).toBe(`하루들_2026-08-12_${character.repeat(20)}.png`);
      expect(Buffer.byteLength(fileName, 'utf8')).toBeLessThanOrEqual(127);
      expect(await download.failure()).toBeNull();
    });
  }

  test('이미지 저장에 실패하면 에러 메시지를 보여준다', async ({ page }) => {
    await goToSampleDiaryDetail(page);
    const diaryImageUrl = await page
      .getByRole('img', { name: '네컷만화' })
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
      .getByRole('button', { name: DIARY_DETAIL_COPY.downloadAction })
      .click();
    const errorDialog = await errorPromise;

    expect(errorDialog.message()).toBe(ERROR_MESSAGES.DIARY_IMAGE_SAVE_FAILED);
    await errorDialog.accept();
    await downloadClickPromise;
  });
});
