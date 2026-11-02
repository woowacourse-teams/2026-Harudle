import type { WebViewMessageEvent } from 'react-native-webview';
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
    case 'auth.apple.signIn': {
      const result = await handleAppleSignInMessage(message);
      if (result) onAppleSignInResult(result);
      return;
    }
    default:
      return;
  }
};
