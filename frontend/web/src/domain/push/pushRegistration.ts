import { API_BASE_URL, isProblemDetails, RequestError } from '../../shared/api';
import { authFetch } from '../../shared/auth';
import { isRecord, isNonNegativeInteger } from '../../shared/utils';
import { PUSH_COPY } from './copy';

const PREFERENCE_KEY = 'harudle.push.preference';
const DEVICE_KEY = 'harudle.push.deviceId';

export interface PushState {
  readonly registration: {
    readonly id: string;
    readonly version: number;
  } | null;
  readonly stateVersion: number;
}

export const getPushPreference = (userId: string): boolean | null => {
  try {
    const value: unknown = JSON.parse(
      localStorage.getItem(PREFERENCE_KEY) ?? 'null',
    );
    return isRecord(value) &&
      value.userId === userId &&
      typeof value.enabled === 'boolean'
      ? value.enabled
      : null;
  } catch {
    return null;
  }
};

export const setPushPreference = (userId: string, enabled: boolean): void => {
  localStorage.setItem(PREFERENCE_KEY, JSON.stringify({ userId, enabled }));
};

export const clearNativePushRegistration = (): void => {
  // 서버 로그아웃이 끝난 뒤의 기기 정리 실패로 로그아웃을 실패로 바꾸지 않는다.
  try {
    localStorage.removeItem(PREFERENCE_KEY);
  } catch {
    /* 서버는 이미 해당 세션의 푸시 등록을 해제했다. */
  }
  try {
    window.ReactNativeWebView?.postMessage(
      JSON.stringify({ type: 'push.unregister' }),
    );
  } catch (error: unknown) {
    console.warn('[push] native cleanup failed', error);
  }
};

export const getPushDeviceId = (): string => {
  const saved = localStorage.getItem(DEVICE_KEY);
  if (
    saved &&
    /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(
      saved,
    )
  )
    return saved;
  const id = crypto.randomUUID();
  localStorage.setItem(DEVICE_KEY, id);
  return id;
};

const isRegistration = (
  value: unknown,
): value is NonNullable<PushState['registration']> => {
  return (
    isRecord(value) &&
    typeof value.id === 'string' &&
    value.id.length > 0 &&
    typeof value.version === 'number' &&
    isNonNegativeInteger(value.version)
  );
};

const waitForRetry = (delay: number, signal: AbortSignal): Promise<void> => {
  signal.throwIfAborted();
  return new Promise((resolve, reject): void => {
    const onAbort = (): void => {
      window.clearTimeout(timer);
      reject(signal.reason);
    };
    const timer = window.setTimeout((): void => {
      signal.removeEventListener('abort', onAbort);
      resolve();
    }, delay);
    signal.addEventListener('abort', onAbort, { once: true });
  });
};

export const updatePushRegistration = async (
  deviceId: string,
  fid: string | null,
  previous: PushState,
  signal: AbortSignal,
): Promise<PushState> => {
  let state = previous;
  for (let attempt = 0; attempt < 3; attempt++) {
    signal.throwIfAborted();
    let response: Response;
    try {
      const url = `${API_BASE_URL}/me/push-registrations/${deviceId}`;
      response = await authFetch(
        fid === null ? `${url}?stateVersion=${state.stateVersion}` : url,
        {
          method: fid === null ? 'DELETE' : 'PUT',
          signal,
          ...(fid === null
            ? {}
            : {
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({
                  fid,
                  stateVersion: state.stateVersion,
                  ...(state.registration
                    ? {
                        registrationId: state.registration.id,
                        version: state.registration.version,
                      }
                    : {}),
                }),
              }),
        },
      );
    } catch (error: unknown) {
      if (!(error instanceof TypeError) || attempt === 2 || signal.aborted)
        throw error;
      await waitForRetry(1000 * 2 ** attempt, signal);
      continue;
    }

    if (fid === null && response.status === 204) {
      const header = response.headers.get('Push-State-Version');
      const version =
        header === null || header.trim() === '' ? NaN : Number(header);
      if (!isNonNegativeInteger(version))
        throw new Error(PUSH_COPY.invalidResponse);
      return { registration: null, stateVersion: version };
    }
    const data: unknown = await response.json().catch(() => null);
    if (fid !== null && response.status === 200) {
      if (
        !isRegistration(data) ||
        !('stateVersion' in data) ||
        typeof data.stateVersion !== 'number' ||
        !isNonNegativeInteger(data.stateVersion)
      ) {
        throw new Error(PUSH_COPY.invalidResponse);
      }
      return {
        registration: { id: data.id, version: data.version },
        stateVersion: data.stateVersion,
      };
    }
    if (response.status === 409 && isRecord(data)) {
      if (
        data.code === 'STALE_PUSH_STATE' &&
        typeof data.currentStateVersion === 'number' &&
        isNonNegativeInteger(data.currentStateVersion)
      ) {
        state = { ...state, stateVersion: data.currentStateVersion };
        continue;
      }
      if (
        data.code === 'STALE_PUSH_REGISTRATION' &&
        (data.current === null || isRegistration(data.current))
      ) {
        state = { ...state, registration: data.current };
        continue;
      }
    }
    if ((response.status === 429 || response.status >= 500) && attempt < 2) {
      const retryAfter = response.headers.get('Retry-After');
      const delay =
        retryAfter === null
          ? 1000 * 2 ** attempt
          : /^\d+$/.test(retryAfter)
            ? Number(retryAfter) * 1000
            : Date.parse(retryAfter) - Date.now();
      await waitForRetry(
        Number.isFinite(delay) ? Math.max(0, delay) : 1000 * 2 ** attempt,
        signal,
      );
      continue;
    }
    if (isProblemDetails(data)) throw new RequestError(data);
    throw new Error(PUSH_COPY.failed);
  }
  throw new Error(PUSH_COPY.failed);
};
