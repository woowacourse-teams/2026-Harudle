import {
  getMessaging,
  onRegistered,
  register,
  unregister,
} from '@react-native-firebase/messaging';
import {
  AndroidImportance,
  getPermissionsAsync,
  requestPermissionsAsync,
  setNotificationChannelAsync,
} from 'expo-notifications';
import { Platform } from 'react-native';

export type PushRegistrationResult =
  | {
      readonly userId: string;
      readonly status: 'registered';
      readonly fid: string;
    }
  | { readonly userId: string; readonly status: 'denied' }
  | { readonly userId: string; readonly status: 'error' };

interface PushRequest {
  readonly type: 'push.register' | 'push.sync';
  readonly payload: { readonly userId: string };
}

let unsubscribe: (() => void) | undefined;
let currentUserId: string | null = null;
let requestVersion = 0;
let operation = Promise.resolve();

const isPushRequest = (message: unknown): message is PushRequest => {
  return (
    typeof message === 'object' &&
    message !== null &&
    'type' in message &&
    (message.type === 'push.register' || message.type === 'push.sync') &&
    'payload' in message &&
    typeof message.payload === 'object' &&
    message.payload !== null &&
    'userId' in message.payload &&
    typeof message.payload.userId === 'string' &&
    message.payload.userId.trim().length > 0
  );
};

export const stopPushListener = (): void => {
  requestVersion++;
  unsubscribe?.();
  unsubscribe = undefined;
};

export const handlePushMessage = async (
  message: unknown,
  onResult: (result: PushRegistrationResult) => void,
): Promise<void> => {
  const isUnregister =
    typeof message === 'object' &&
    message !== null &&
    'type' in message &&
    message.type === 'push.unregister';
  if (isUnregister) {
    stopPushListener();
    currentUserId = null;
    operation = operation.then(async (): Promise<void> => {
      try {
        await unregister(getMessaging());
      } catch (error: unknown) {
        if (__DEV__) console.warn('[push] unregister failed', error);
      }
    });
    return operation;
  }
  if (!isPushRequest(message)) return;
  stopPushListener();
  const version = requestVersion;
  const { userId } = message.payload;
  currentUserId = userId;

  operation = operation.then(async (): Promise<void> => {
    if (version !== requestVersion) return;
    try {
      if (Platform.OS === 'android') {
        await setNotificationChannelAsync('feed', {
          name: '새 네컷만화',
          importance: AndroidImportance.DEFAULT,
        });
      }
      let permission = await getPermissionsAsync();
      // 웹에서 이미 수신에 동의했어도, 앱 업데이트 직후에는 OS 권한을 아직 묻지 않았을 수 있다.
      if (
        message.type === 'push.register' ||
        permission.status === 'undetermined'
      ) {
        permission = await requestPermissionsAsync();
      }
      if (version !== requestVersion) return;
      if (!permission.granted) {
        currentUserId = null;
        onResult({ userId, status: 'denied' });
        // Firebase 연결 실패와 관계없이 웹은 서버의 발송 대상을 해제한다.
        try {
          await unregister(getMessaging());
        } catch (error: unknown) {
          if (__DEV__) console.warn('[push] unregister failed', error);
        }
        return;
      }

      const messaging = getMessaging();
      unsubscribe = onRegistered(messaging, (fid): void => {
        if (version === requestVersion)
          onResult({ userId, status: 'registered', fid });
      });
      await register(messaging);
    } catch (error: unknown) {
      if (version !== requestVersion) return;
      stopPushListener();
      if (__DEV__) console.warn('[push] registration failed', error);
      onResult({ userId, status: 'error' });
    }
  });
  return operation;
};

export const refreshPushRegistration = (
  onResult: (result: PushRegistrationResult) => void,
): void => {
  if (currentUserId) {
    void handlePushMessage(
      { type: 'push.sync', payload: { userId: currentUserId } },
      onResult,
    );
  }
};
