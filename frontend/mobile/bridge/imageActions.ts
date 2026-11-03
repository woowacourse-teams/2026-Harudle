import * as Clipboard from 'expo-clipboard';
import { File, Paths } from 'expo-file-system';
import { ImageManipulator, SaveFormat } from 'expo-image-manipulator';
import { Asset, requestPermissionsAsync } from 'expo-media-library';
import { Platform } from 'react-native';

interface ImageActionMessage {
  readonly id: string;
  readonly type: 'image.save' | 'image.copy';
  readonly payload: { readonly imageUrl: string };
}

export interface ImageActionResult {
  readonly id: string;
  readonly type: 'image.result';
  readonly payload:
    | { readonly status: 'success' }
    | { readonly status: 'error'; readonly message: string };
}

const isImageActionMessage = (value: unknown): value is ImageActionMessage => {
  return (
    typeof value === 'object' &&
    value !== null &&
    'id' in value &&
    typeof value.id === 'string' &&
    value.id.length > 0 &&
    'type' in value &&
    (value.type === 'image.save' || value.type === 'image.copy') &&
    'payload' in value &&
    typeof value.payload === 'object' &&
    value.payload !== null &&
    'imageUrl' in value.payload &&
    typeof value.payload.imageUrl === 'string'
  );
};

export const handleImageAction = async (
  message: unknown,
): Promise<ImageActionResult | null> => {
  if (!isImageActionMessage(message)) return null;

  const source = new File(
    Paths.cache,
    `harudle-${Date.now()}-${Math.random().toString(36).slice(2)}.image`,
  );
  let png: File | undefined;
  let step: 'url' | 'permission' | 'download' | 'convert' | 'save' | 'copy' =
    'url';
  let errorMessage =
    message.type === 'image.save'
      ? '이미지를 저장하지 못했어요. 다시 시도해 주세요.'
      : '이미지를 복사하지 못했어요. 다시 시도해 주세요.';

  try {
    const url = new URL(message.payload.imageUrl);
    if (url.protocol !== 'https:' || url.username || url.password) {
      throw new Error('Invalid image URL');
    }

    // Android 10 이상은 앱이 새 이미지를 저장할 때 저장소 권한이 필요 없다.
    if (
      message.type === 'image.save' &&
      (Platform.OS === 'ios' || Number(Platform.Version) < 29)
    ) {
      step = 'permission';
      const permission = await requestPermissionsAsync(true);
      if (!permission.granted) {
        errorMessage =
          '사진 저장 권한이 필요해요. 휴대폰 설정에서 허용해 주세요.';
        throw new Error('Photo permission denied');
      }
    }

    step = 'download';
    await File.downloadFileAsync(url.href, source);
    step = 'convert';
    const context = ImageManipulator.manipulate(source.uri);
    const image = await context.renderAsync();
    try {
      const result = await image.saveAsync({
        format: SaveFormat.PNG,
        base64: message.type === 'image.copy',
      });
      png = new File(result.uri);

      step = message.type === 'image.save' ? 'save' : 'copy';
      if (message.type === 'image.save') {
        await Asset.create(png.uri);
      } else {
        if (!result.base64) throw new Error('Missing image data');
        await Clipboard.setImageAsync(result.base64);
      }
    } finally {
      image.release();
      context.release();
    }

    return {
      id: message.id,
      type: 'image.result',
      payload: { status: 'success' },
    };
  } catch (error: unknown) {
    if (__DEV__) {
      console.warn('[imageActions] failed', {
        requestId: message.id,
        action: message.type,
        step,
        error:
          error instanceof Error
            ? error.message.replace(/https?:\/\/\S+/g, '[image URL]')
            : 'Unknown error',
      });
    }
    return {
      id: message.id,
      type: 'image.result',
      payload: { status: 'error', message: errorMessage },
    };
  } finally {
    // 정리 실패로 이미 끝난 저장, 복사를 실패로 보고하지 않는다.
    try {
      if (source.exists) source.delete();
      if (png?.exists) png.delete();
    } catch {
      /* 캐시 파일은 OS 정리에 맡긴다. */
    }
  }
};
