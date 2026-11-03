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

const NativePushRegistration = () => {
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
      if (!isRecord(payload) || payload.userId !== userId) return;
      if (payload.status === 'error') {
        alert(PUSH_COPY.failed);
        return;
      }
      let fid: string | null;
      if (
        payload.status === 'registered' &&
        typeof payload.fid === 'string' &&
        payload.fid.trim()
      ) {
        if (getPushPreference(userId) !== true) return;
        fid = payload.fid;
      } else if (payload.status === 'denied') {
        setPushPreference(userId, false);
        fid = null;
      } else return;

      queue = queue
        .then(async (): Promise<void> => {
          if (controller.signal.aborted || !userId) return;
          if (
            fid !== null &&
            (getPushPreference(userId) !== true || fid === savedFid)
          )
            return;
          state = await updatePushRegistration(
            getPushDeviceId(),
            fid,
            state,
            controller.signal,
          );
          savedFid = fid;
        })
        .catch((error: unknown): void => {
          if (controller.signal.aborted) return;
          console.warn('[push] server registration failed', error);
          alert(PUSH_COPY.failed);
        });
    };

    window.addEventListener('harudle:message', handleNativeMessage);
    void getUserProfile()
      .then((profile): void => {
        if (controller.signal.aborted) return;
        userId = profile.id;
        const preference = getPushPreference(userId);
        if (preference === false) return;
        if (preference === null) {
          const enabled = window.confirm(PUSH_COPY.confirm);
          setPushPreference(userId, enabled);
          if (!enabled) return;
        }
        window.ReactNativeWebView?.postMessage(
          JSON.stringify({
            type: preference === true ? 'push.sync' : 'push.register',
            payload: { userId },
          }),
        );
      })
      .catch((error: unknown): void => {
        if (!controller.signal.aborted)
          console.warn('[push] setup failed', error);
      });

    return (): void => {
      controller.abort();
      window.removeEventListener('harudle:message', handleNativeMessage);
    };
  }, []);

  return null;
};

export default NativePushRegistration;
