import { useEffect } from 'react';
import { isNativeApp } from '../../shared/bridge';
import { isRecord } from '../../shared/utils';
import { getUserProfile } from '../user/profile';
import { PUSH_COPY } from './copy';
import {
  getPushPreference,
  setPushPreference,
  getPushDeviceId,
  updatePushRegistration,
  type PushState,
} from './pushRegistration';

export interface NativePushControls {
  readonly enabled: boolean;
  readonly pending: boolean;
  readonly changeEnabled: (enabled: boolean) => void;
}

interface Props {
  readonly onChange?: (controls: NativePushControls | null) => void;
}

const NativePushRegistration = ({ onChange }: Props) => {
  useEffect((): (() => void) | undefined => {
    if (
      !isNativeApp() ||
      !window.ReactNativeWebView ||
      localStorage.getItem('harudle.has-completed-oauth') === null
    )
      return;
    const controller = new AbortController();
    let userId: string | null = null;
    let state: PushState = { registration: null, stateVersion: 0 };
    let savedFid: string | null = null;
    let queue = Promise.resolve();
    let enabled = false;
    let pending = false;

    const publish = (): void => {
      if (!controller.signal.aborted)
        onChange?.({ enabled, pending, changeEnabled });
    };
    const fail = (error: unknown): void => {
      if (
        controller.signal.aborted ||
        !userId ||
        getPushPreference(userId) === null
      )
        return;
      setPushPreference(userId, enabled);
      pending = false;
      publish();
      console.warn('[push] registration failed', error);
      alert(PUSH_COPY.failed);
    };
    const saveRegistration = (fid: string | null): void => {
      queue = queue
        .then(async (): Promise<void> => {
          if (controller.signal.aborted || !userId) return;
          const preference = getPushPreference(userId);
          if (preference === null || (fid !== null && preference !== true))
            return;
          if (fid !== null && fid === savedFid) return;
          pending = true;
          publish();
          state = await updatePushRegistration(
            getPushDeviceId(),
            fid,
            state,
            controller.signal,
          );
          if (controller.signal.aborted || getPushPreference(userId) === null)
            return;
          savedFid = fid;
          enabled = fid !== null;
          pending = false;
          if (fid === null)
            window.ReactNativeWebView?.postMessage(
              JSON.stringify({ type: 'push.unregister' }),
            );
          publish();
        })
        .catch(fail);
    };
    const requestRegistration = (type: 'push.register' | 'push.sync'): void => {
      pending = true;
      publish();
      window.ReactNativeWebView?.postMessage(
        JSON.stringify({ type, payload: { userId } }),
      );
    };
    function changeEnabled(next: boolean): void {
      if (!userId || pending || controller.signal.aborted) return;
      setPushPreference(userId, next);
      if (next) requestRegistration('push.register');
      else {
        pending = true;
        publish();
        saveRegistration(null);
      }
    }

    const handleNativeMessage = (event: Event): void => {
      if (
        !(event instanceof CustomEvent) ||
        !userId ||
        controller.signal.aborted
      )
        return;
      const message: unknown = event.detail;
      if (!isRecord(message) || message.type !== 'push.registrationResult')
        return;
      const payload = message.payload;
      if (
        !isRecord(payload) ||
        payload.userId !== userId ||
        getPushPreference(userId) === null
      )
        return;
      if (payload.status === 'error') {
        if (getPushPreference(userId) === true)
          fail(new Error('Native push registration failed'));
        return;
      }
      if (
        payload.status === 'registered' &&
        typeof payload.fid === 'string' &&
        payload.fid.trim()
      ) {
        if (getPushPreference(userId) === true) saveRegistration(payload.fid);
      } else if (payload.status === 'denied') {
        enabled = false;
        setPushPreference(userId, false);
        alert(PUSH_COPY.permissionDenied);
        saveRegistration(null);
      }
    };

    window.addEventListener('harudle:message', handleNativeMessage);
    void getUserProfile()
      .then((profile): void => {
        if (controller.signal.aborted) return;
        userId = profile.id;
        const preference = getPushPreference(userId);
        publish();
        if (preference === false) {
          pending = true;
          publish();
          saveRegistration(null);
          return;
        }
        if (preference === null) {
          const accepted = window.confirm(PUSH_COPY.confirm);
          setPushPreference(userId, accepted);
          if (!accepted) return;
        }
        requestRegistration(
          preference === true ? 'push.sync' : 'push.register',
        );
      })
      .catch((error: unknown): void => {
        if (!controller.signal.aborted)
          console.warn('[push] setup failed', error);
      });

    return (): void => {
      controller.abort();
      window.removeEventListener('harudle:message', handleNativeMessage);
      onChange?.(null);
    };
  }, [onChange]);

  return null;
};

export default NativePushRegistration;
