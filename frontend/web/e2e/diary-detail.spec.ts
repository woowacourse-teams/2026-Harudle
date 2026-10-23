import { FEED_ACTION_COPY } from '../src/shared/feed/copy';
import { DIARY_DETAIL_COPY } from '../src/pages/diary-detail/copy';
import { ALBUM_COPY } from '../src/pages/album/copy';
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

test.beforeEach(async ({ page }): Promise<void> => {
  await page.clock.setFixedTime(TODAY);
});

const setMockScenario = async (
  page: Page,
  scenario?: (typeof MOCK_SCENARIOS)[keyof typeof MOCK_SCENARIOS],
) => {
  await page.setExtraHTTPHeaders(
    scenario ? { [MOCK_SCENARIO_HEADER]: scenario } : {},
  );
};

const goToAlbum = async (page: Page) => {
  await page.clock.setFixedTime(TODAY);
  await page.goto('/album');
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
  test('앨범에서 일기를 클릭하면 로딩 후 상세 정보를 확인할 수 있다', async ({
    page,
  }) => {
    await goToAlbum(page);
    await clickSampleDiary(page);
    const loadingSpinner = page.getByRole('img', { name: '로딩 중' });

    await expect(loadingSpinner).toBeVisible();
    await expectSampleDiaryDetail(page);
    await expect(loadingSpinner).toBeHidden();
  });

  test('상세 정보 조회에 실패하면 다시 불러올 수 있다', async ({ page }) => {
    await setMockScenario(page, MOCK_SCENARIOS.diaryDetailFailure);
    await goToAlbum(page);
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

  test('상단 뒤로가기 버튼을 누르면 앨범 화면으로 이동한다', async ({
    page,
  }) => {
    await goToAlbum(page);
    await clickSampleDiary(page);

    await page.getByRole('button', { name: '뒤로 가기' }).click();

    await expect(page).toHaveURL('/album');
    await expect(page.getByLabel('조회할 월')).toBeVisible();
  });

  test('다른 월에서 상세 화면에 진입한 뒤 돌아가면 선택한 월을 유지한다', async ({
    page,
  }) => {
    await page.goto('/album?yearMonth=2026-07');

    const monthInput = page.getByLabel('조회할 월');
    await monthInput.fill('2026-08');
    await expect(page).toHaveURL('/album?yearMonth=2026-08');

    await clickSampleDiary(page);
    await page.getByRole('button', { name: '뒤로 가기' }).click();

    await expect(page).toHaveURL('/album?yearMonth=2026-08');
    await expect(monthInput).toHaveValue('2026-08');
  });

  test('삭제를 확인하면 일기를 삭제하고 앨범 화면으로 이동한다', async ({
    page,
  }) => {
    await goToSampleDiaryDetail(page);
    await page
      .getByRole('button', { name: DIARY_DETAIL_COPY.menuAction })
      .click();

    await page
      .getByRole('button', { name: DIARY_DETAIL_COPY.deleteAction })
      .click();
    const confirmModal = page.getByRole('dialog').filter({
      has: page.getByRole('heading', {
        name: DIARY_DETAIL_COPY.deleteConfirmTitle,
        exact: true,
      }),
    });
    await expect(
      confirmModal.getByText(DIARY_DETAIL_COPY.deleteConfirm, { exact: true }),
    ).toBeVisible();
    await confirmModal
      .getByRole('button', { name: DIARY_DETAIL_COPY.deleteAction })
      .click();

    await expect(page).toHaveURL('/album');
    await expect(
      page.getByText(
        `${ALBUM_COPY.monthlyCount.before}5${ALBUM_COPY.monthlyCount.after}`,
      ),
    ).toBeVisible();
    await expect(
      page.getByText(SAMPLE_DIARY_TITLE, { exact: true }),
    ).toHaveCount(0);
  });

  test('네컷 상세에서 피드 삭제: 실패하면 열린 모달 안에 오류를 표시하고 재시도할 수 있다', async ({
    page,
  }): Promise<void> => {
    await page.setExtraHTTPHeaders({
      [MOCK_SCENARIO_HEADER]: MOCK_SCENARIOS.feedDeleteFailure,
    });
    await page.goto('/diary/00000000-0000-4000-8000-000000000031');
    await page
      .getByRole('button', {
        name: DIARY_DETAIL_COPY.feedDeleteAction,
        exact: true,
      })
      .click();
    const modal = page.getByRole('dialog').filter({
      has: page.getByRole('heading', {
        name: FEED_ACTION_COPY.deleteConfirmTitle,
        exact: true,
      }),
    });
    const submit = modal.getByRole('button', {
      name: DIARY_DETAIL_COPY.feedDeleteAction,
      exact: true,
    });
    await submit.click();

    await expect(modal).toBeVisible();
    await expect(modal.getByRole('alert')).toHaveText(
      ERROR_MESSAGES.FEED_DELETION_FAILED,
    );
    await expect(modal.getByRole('alert')).toBeVisible();
    await expect(
      page.getByText(ERROR_MESSAGES.FEED_DELETION_FAILED, { exact: true }),
    ).toHaveCount(1);
    await expect(page).toHaveURL('/diary/00000000-0000-4000-8000-000000000031');
    await expect(
      modal.getByRole('button', { name: '취소', exact: true }),
    ).toBeEnabled();
    await expect(submit).toBeEnabled();

    await page.setExtraHTTPHeaders({});
    await submit.click();
    await expect(modal).toBeHidden();
    await expect(
      page.getByText(ERROR_MESSAGES.FEED_DELETION_FAILED, { exact: true }),
    ).toHaveCount(0);
  });

  test('일기 삭제에 실패하면 모달 내부에 에러 메시지를 보여준다', async ({
    page,
  }) => {
    await goToSampleDiaryDetail(page, MOCK_SCENARIOS.diaryDeleteFailure);
    await page
      .getByRole('button', { name: DIARY_DETAIL_COPY.menuAction })
      .click();

    await page
      .getByRole('button', { name: DIARY_DETAIL_COPY.deleteAction })
      .click();
    const confirmModal = page.getByRole('dialog').filter({
      has: page.getByRole('heading', {
        name: DIARY_DETAIL_COPY.deleteConfirmTitle,
        exact: true,
      }),
    });
    await confirmModal
      .getByRole('button', { name: DIARY_DETAIL_COPY.deleteAction })
      .click();
    await expect(confirmModal).toBeVisible();
    await expect(confirmModal.getByRole('alert')).toHaveText(
      '일기 삭제에 실패했습니다. 다시 시도해주세요.',
    );
    await expect(confirmModal.getByRole('alert')).toBeVisible();

    await expect(page).toHaveURL(SAMPLE_DIARY_URL);
    await expect(
      page.getByText(SAMPLE_DIARY_TITLE, { exact: true }),
    ).toBeVisible();
    await expect(
      page.getByText('일기 삭제에 실패했습니다. 다시 시도해주세요.', {
        exact: true,
      }),
    ).toHaveCount(1);
    await expect(
      confirmModal.getByRole('button', { name: '취소', exact: true }),
    ).toBeEnabled();
    const submit = confirmModal.getByRole('button', {
      name: DIARY_DETAIL_COPY.deleteAction,
      exact: true,
    });
    await expect(submit).toBeEnabled();
    await setMockScenario(page);
    await submit.click();
    await expect(confirmModal).toBeHidden();
    await expect(page).toHaveURL('/album');
    await expect(
      page.getByText('일기 삭제에 실패했습니다. 다시 시도해주세요.', {
        exact: true,
      }),
    ).toHaveCount(0);
  });

  test('삭제 확인 모달에서 취소하면 삭제 요청 없이 상세 화면을 유지한다', async ({
    page,
  }): Promise<void> => {
    await goToSampleDiaryDetail(page);
    let deleteRequestCount = 0;
    page.on('request', (request): void => {
      if (
        request.method() === 'DELETE' &&
        new URL(request.url()).pathname === `/api/v1/diaries/${SAMPLE_DIARY_ID}`
      ) {
        deleteRequestCount += 1;
      }
    });

    await page
      .getByRole('button', { name: DIARY_DETAIL_COPY.menuAction })
      .click();
    await page
      .getByRole('button', { name: DIARY_DETAIL_COPY.deleteAction })
      .click();
    const confirmModal = page.getByRole('dialog').filter({
      has: page.getByRole('heading', {
        name: DIARY_DETAIL_COPY.deleteConfirmTitle,
        exact: true,
      }),
    });
    await confirmModal
      .getByRole('button', { name: '취소', exact: true })
      .click();

    await expect(confirmModal).toBeHidden();
    await expectSampleDiaryDetail(page);
    expect(deleteRequestCount).toBe(0);
  });

  test('피드에 올리기를 누르면 해당 일기의 피드 게시 화면으로 이동한다', async ({
    page,
  }) => {
    await goToSampleDiaryDetail(page);

    await page
      .getByRole('button', { name: DIARY_DETAIL_COPY.feedPostAction })
      .click();

    await expect(page).toHaveURL(`${SAMPLE_DIARY_URL}/feed-post`);
    await expect(page.getByRole('img', { name: '네컷만화' })).toBeVisible();
    await expect(page.getByRole('radio', { name: '일상' })).not.toBeChecked();
    await expect(
      page.getByText(SAMPLE_DIARY_TITLE, { exact: true }),
    ).toHaveCount(0);

    await page.reload();
    await expect(page.getByRole('img', { name: '네컷만화' })).toBeVisible();
    await expect(page.getByRole('radio', { name: '일상' })).not.toBeChecked();

    await page.goBack();
    await expectSampleDiaryDetail(page);
  });

  test('이미지 저장 버튼을 누르면 일기 이미지를 저장한다', async ({ page }) => {
    await goToSampleDiaryDetail(page);

    const downloadPromise = page.waitForEvent('download');
    await page
      .getByRole('button', { name: DIARY_DETAIL_COPY.downloadAction })
      .click();
    const download = await downloadPromise;

    expect(download.suggestedFilename()).toBe(
      '하루들_2026-08-12_비가 와도, 나는 괜찮았다.webp',
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

      expect(fileName).toBe(`하루들_2026-08-12_${character.repeat(20)}.webp`);
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
