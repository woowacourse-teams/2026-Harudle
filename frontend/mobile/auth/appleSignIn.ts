import * as AppleAuthentication from 'expo-apple-authentication';
import { Alert } from 'react-native';

export interface AppleSignInMessage {
  readonly type: 'auth.apple.signIn';
  readonly payload: {
    readonly nonce: string;
  };
}

export interface AppleSignInResult {
  readonly authorizationCode: string;
  readonly identityToken: string;
}

const isAppleSignInMessage = (value: unknown): value is AppleSignInMessage => {
  return (
    typeof value === 'object' &&
    value !== null &&
    'type' in value &&
    value.type === 'auth.apple.signIn' &&
    'payload' in value &&
    typeof value.payload === 'object' &&
    value.payload !== null &&
    'nonce' in value.payload &&
    typeof value.payload.nonce === 'string' &&
    value.payload.nonce.trim().length > 0
  );
};

export const handleAppleSignInMessage = async (
  message: unknown,
): Promise<AppleSignInResult | null> => {
  if (!isAppleSignInMessage(message)) return null;

  try {
    if (!(await AppleAuthentication.isAvailableAsync())) {
      Alert.alert('이 기기에서는 Apple 로그인을 사용할 수 없습니다.');
      return null;
    }

    const { authorizationCode, identityToken } =
      await AppleAuthentication.signInAsync({
        nonce: message.payload.nonce,
        requestedScopes: [AppleAuthentication.AppleAuthenticationScope.EMAIL],
      });

    if (!authorizationCode || !identityToken) {
      throw new Error('Apple 인증 정보를 받지 못했습니다.');
    }

    return { authorizationCode, identityToken };
  } catch (error: unknown) {
    if (
      typeof error === 'object' &&
      error !== null &&
      'code' in error &&
      error.code === 'ERR_REQUEST_CANCELED'
    ) {
      return null;
    }

    Alert.alert('Apple 로그인에 실패했습니다.');
    return null;
  }
};
