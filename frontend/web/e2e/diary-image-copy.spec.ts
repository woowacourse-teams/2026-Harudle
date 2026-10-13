import { expect, test, type Page } from '@playwright/test';
import { AUTHENTICATED_STORAGE_STATE } from './auth';
import { DIARY_DETAIL_COPY } from '../src/pages/diary-detail/copy';
import { ERROR_MESSAGES } from '../src/shared/errorMessage';

test.use({ storageState: AUTHENTICATED_STORAGE_STATE });

const goToDiary = async (page: Page): Promise<void> => {
  await page.clock.setFixedTime(new Date('2026-10-05T12:00:00+09:00'));
  await page.goto('/diary/00000000-0000-4000-8000-000000000001');
  await expect(
    page.getByRole('button', { name: DIARY_DETAIL_COPY.copyAction }),
  ).toBeVisible();
};

test('WebP를 원본 크기의 PNG만으로 복사하고, 이미지 준비 전에 클립보드 쓰기를 요청한다', async ({
  page,
}): Promise<void> => {
  await goToDiary(page);
  const imageUrl = await page
    .getByRole('img', { name: '네컷만화' })
    .evaluate((image: HTMLImageElement): string => image.src);

  await page.evaluate((sourceUrl): void => {
    const originalFetch = window.fetch.bind(window);
    window.fetch = async (input, init): Promise<Response> => {
      const url =
        input instanceof Request
          ? input.url
          : new URL(input, location.href).href;
      if (url === sourceUrl) {
        await new Promise<void>((resolve): void => {
          window.setTimeout(resolve, 500);
        });
      }
      return originalFetch(input, init);
    };

    Object.defineProperty(navigator.clipboard, 'write', {
      configurable: true,
      value: async (items: ClipboardItem[]): Promise<void> => {
        const userActivation = navigator.userActivation.isActive;
        const png = await items[0].getType('image/png');
        const buffer = await png.arrayBuffer();
        const bytes = new Uint8Array(buffer);
        const header = new DataView(buffer);
        sessionStorage.setItem(
          'copiedImage',
          JSON.stringify({
            userActivation,
            itemCount: items.length,
            types: items[0].types,
            blobType: png.type,
            signature: Array.from(bytes.slice(0, 8)),
            width: header.getUint32(16),
            height: header.getUint32(20),
          }),
        );
      },
    });
  }, imageUrl);

  await page
    .getByRole('button', { name: DIARY_DETAIL_COPY.copyAction })
    .click();
  await expect(
    page.getByRole('button', { name: DIARY_DETAIL_COPY.copyAction }),
  ).toBeEnabled();
  await expect
    .poll(() => page.evaluate(() => sessionStorage.getItem('copiedImage')))
    .not.toBeNull();
  await expect(
    page.getByRole('button', { name: DIARY_DETAIL_COPY.copySuccess }),
  ).toBeEnabled();

  const copiedImage: unknown = await page.evaluate(() =>
    JSON.parse(sessionStorage.getItem('copiedImage') ?? '{}'),
  );
  expect(copiedImage).toEqual({
    userActivation: true,
    itemCount: 1,
    types: ['image/png'],
    blobType: 'image/png',
    signature: [137, 80, 78, 71, 13, 10, 26, 10],
    width: 960,
    height: 960,
  });
});

for (const failure of ['http', 'decode', 'encode'] as const) {
  test(`이미지 ${failure} 실패 시 오류를 알리고 임시 이미지 URL을 정리한다`, async ({
    page,
  }): Promise<void> => {
    await goToDiary(page);
    const imageUrl = await page
      .getByRole('img', { name: '네컷만화' })
      .evaluate((image: HTMLImageElement): string => image.src);

    await page.evaluate(
      ({ sourceUrl, failure }): void => {
        const originalFetch = window.fetch.bind(window);
        window.fetch = async (input, init): Promise<Response> => {
          const url =
            input instanceof Request
              ? input.url
              : new URL(input, location.href).href;
          if (url === sourceUrl && failure === 'http') {
            return new Response(null, { status: 503 });
          }
          if (url === sourceUrl && failure === 'decode') {
            return new Response('invalid image', {
              headers: { 'Content-Type': 'image/webp' },
            });
          }
          return originalFetch(input, init);
        };
        if (failure === 'encode') {
          HTMLCanvasElement.prototype.toBlob = (callback): void =>
            callback(null);
        }

        const createdUrls: string[] = [];
        const revokedUrls: string[] = [];
        const createObjectURL = URL.createObjectURL.bind(URL);
        const revokeObjectURL = URL.revokeObjectURL.bind(URL);
        URL.createObjectURL = (blob): string => {
          const url = createObjectURL(blob);
          createdUrls.push(url);
          sessionStorage.setItem('createdUrls', JSON.stringify(createdUrls));
          return url;
        };
        URL.revokeObjectURL = (url): void => {
          revokedUrls.push(url);
          sessionStorage.setItem('revokedUrls', JSON.stringify(revokedUrls));
          revokeObjectURL(url);
        };
        Object.defineProperty(navigator.clipboard, 'write', {
          configurable: true,
          value: async (items: ClipboardItem[]): Promise<void> => {
            await items[0].getType('image/png');
          },
        });
      },
      { sourceUrl: imageUrl, failure },
    );

    const dialogPromise = page.waitForEvent('dialog');
    const clickPromise = page
      .getByRole('button', { name: DIARY_DETAIL_COPY.copyAction })
      .click();
    const dialog = await dialogPromise;
    expect(dialog.message()).toBe(ERROR_MESSAGES.DIARY_IMAGE_COPY_FAILED);
    await dialog.accept();
    await clickPromise;

    await expect(page.getByRole('status')).toHaveCount(0);
    await expect(
      page.getByRole('button', { name: DIARY_DETAIL_COPY.copyAction }),
    ).toBeEnabled();
    const urls = await page.evaluate(() => ({
      created: sessionStorage.getItem('createdUrls'),
      revoked: sessionStorage.getItem('revokedUrls'),
    }));
    expect(urls.revoked).toBe(urls.created);
    if (failure !== 'http') {
      expect(urls.created).not.toBeNull();
    }
  });
}
