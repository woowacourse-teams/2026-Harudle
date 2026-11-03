import type { WebViewMessageEvent } from 'react-native-webview';
import { handleImageAction, type ImageActionResult } from './imageActions';
import {
  handleAppleSignInMessage,
  type AppleSignInResult,
} from '../auth/appleSignIn';

interface BridgeMessage {
  readonly type: string;
}

const isBridgeMessage = (value: unknown): value is BridgeMessage => {
  return (
    typeof value === 'object' &&
    value !== null &&
    'type' in value &&
    typeof value.type === 'string'
  );
};

export const handleWebViewMessage = async (
  event: WebViewMessageEvent,
  onAppleSignInResult: (result: AppleSignInResult) => void,
  onImageResult: (result: ImageActionResult) => void,
): Promise<void> => {
  let message: unknown;
  try {
    message = JSON.parse(event.nativeEvent.data);
  } catch (error) {
    console.log(error);
    return;
  }

  if (!isBridgeMessage(message)) return;

  switch (message.type) {
    case 'image.save':
    case 'image.copy': {
      // 외부 페이지에서는 앱의 이미지 기능을 실행하지 않는다.
      try {
        if (new URL(event.nativeEvent.url).origin !== 'https://www.harudle.com')
          return;
      } catch {
        return;
      }
      const result = await handleImageAction(message);
      if (result) onImageResult(result);
      return;
    }
    case 'auth.apple.signIn': {
      const result = await handleAppleSignInMessage(message);
      if (result) onAppleSignInResult(result);
      return;
    }
    default:
      return;
  }
};
