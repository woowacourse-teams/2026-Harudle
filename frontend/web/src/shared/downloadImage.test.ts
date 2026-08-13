import { afterEach, describe, expect, it, jest } from '@jest/globals';
import { downloadImage } from './downloadImage';

const originalCreateObjectURL = URL.createObjectURL;
const originalRevokeObjectURL = URL.revokeObjectURL;

const createResponse = (status: number, blob: Blob): Response => {
  return {
    ok: status >= 200 && status < 300,
    blob: jest.fn<() => Promise<Blob>>().mockResolvedValue(blob),
  } as unknown as Response;
};

afterEach(() => {
  delete (globalThis as { fetch?: typeof fetch }).fetch;
  jest.restoreAllMocks();
  jest.useRealTimers();
  URL.createObjectURL = originalCreateObjectURL;
  URL.revokeObjectURL = originalRevokeObjectURL;
});

describe('downloadImage', () => {
  it('이미지를 Blob으로 받아 날짜가 포함된 파일로 저장한다', async () => {
    jest.useFakeTimers();
    const imageBlob = new Blob(['image'], { type: 'image/png' });
    globalThis.fetch = jest
      .fn<typeof fetch>()
      .mockResolvedValue(createResponse(200, imageBlob));
    const click = jest
      .spyOn(HTMLAnchorElement.prototype, 'click')
      .mockImplementation(() => {});
    const createObjectURL = jest.fn<typeof URL.createObjectURL>(
      () => 'blob:image',
    );
    const revokeObjectURL = jest.fn<typeof URL.revokeObjectURL>();
    URL.createObjectURL = createObjectURL;
    URL.revokeObjectURL = revokeObjectURL;

    await downloadImage(
      'https://images.example/diary.png',
      'harudle-2026-08-06',
    );

    const link = click.mock.contexts[0] as HTMLAnchorElement;
    expect(link.href).toBe('blob:image');
    expect(link.download).toBe('harudle-2026-08-06.png');
    expect(link.isConnected).toBe(false);
    expect(createObjectURL).toHaveBeenCalledWith(imageBlob);

    jest.advanceTimersByTime(1_000);
    expect(revokeObjectURL).toHaveBeenCalledWith('blob:image');
  });

  it('이미지 응답이 실패하면 파일을 만들지 않는다', async () => {
    globalThis.fetch = jest
      .fn<typeof fetch>()
      .mockResolvedValue(createResponse(403, new Blob()));
    const click = jest.spyOn(HTMLAnchorElement.prototype, 'click');

    await expect(
      downloadImage('https://images.example/expired.png', 'harudle'),
    ).rejects.toThrow('이미지를 저장하지 못했습니다.');
    expect(click).not.toHaveBeenCalled();
  });
});
