import { isRecord } from '../../shared/utils';
import { NATIVE_IMAGE_COPY } from './copy';

export const requestNativeImage = (
  type: 'image.save' | 'image.copy',
  imageUrl: string,
): Promise<void> => {
  const bridge = window.ReactNativeWebView;
  if (!bridge) {
    return Promise.reject(new Error(NATIVE_IMAGE_COPY.timeout));
  }

  const id = crypto.randomUUID();
  return new Promise<void>((resolve, reject): void => {
    const finish = (error?: Error): void => {
      window.clearTimeout(timeout);
      window.removeEventListener('harudle:message', onResult);
      if (error) reject(error);
      else resolve();
    };

    const onResult = (event: Event): void => {
      if (!(event instanceof CustomEvent)) return;
      const message: unknown = event.detail;
      if (
        !isRecord(message) ||
        message.id !== id ||
        message.type !== 'image.result'
      )
        return;
      const payload = message.payload;
      if (!isRecord(payload)) return;
      if (payload.status === 'success') finish();
      else if (
        payload.status === 'error' &&
        typeof payload.message === 'string'
      ) {
        finish(new Error(payload.message));
      }
    };

    const timeout = window.setTimeout((): void => {
      finish(new Error(NATIVE_IMAGE_COPY.timeout));
    }, 60_000);
    window.addEventListener('harudle:message', onResult);
    try {
      bridge.postMessage(JSON.stringify({ id, type, payload: { imageUrl } }));
    } catch (error: unknown) {
      finish(
        error instanceof Error ? error : new Error(NATIVE_IMAGE_COPY.timeout),
      );
    }
  });
};
