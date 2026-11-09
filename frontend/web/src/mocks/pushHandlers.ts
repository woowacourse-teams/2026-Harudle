import { http, HttpResponse } from 'msw';
import { isRecord } from '../shared/utils';

interface Registration {
  id: string;
  version: number;
  stateVersion: number;
  fid: string | null;
}
const registrations = new Map<string, Registration>();
export const resetPushMockState = (): void => registrations.clear();

const conflict = (code: string, extra: object): Response =>
  HttpResponse.json(
    {
      type: 'about:blank',
      title: code,
      status: 409,
      detail: code,
      instance: '/api/v1/me/push-registrations',
      code,
      ...extra,
    },
    { status: 409 },
  );

export const pushHandlers = [
  http.put(
    '/api/v1/me/push-registrations/:deviceId',
    async ({ request, params }): Promise<Response> => {
      const deviceId = String(params.deviceId);
      const body: unknown = await request.json();
      if (
        !isRecord(body) ||
        typeof body.fid !== 'string' ||
        typeof body.stateVersion !== 'number'
      ) {
        return HttpResponse.json({}, { status: 400 });
      }
      const current = registrations.get(deviceId) ?? {
        id: crypto.randomUUID(),
        version: 0,
        stateVersion: 0,
        fid: null,
      };
      if (
        current.fid === body.fid &&
        (body.stateVersion === current.stateVersion ||
          body.stateVersion === current.stateVersion - 1)
      ) {
        return HttpResponse.json(current);
      }
      if (body.stateVersion !== current.stateVersion) {
        return conflict('STALE_PUSH_STATE', {
          currentStateVersion: current.stateVersion,
          enabled: current.fid !== null,
        });
      }
      if (
        current.fid !== null &&
        (body.registrationId !== current.id || body.version !== current.version)
      ) {
        return conflict('STALE_PUSH_REGISTRATION', {
          current: { id: current.id, version: current.version },
        });
      }
      const next = {
        ...current,
        fid: body.fid,
        stateVersion: current.stateVersion + 1,
        version: current.fid === null ? current.version : current.version + 1,
      };
      registrations.set(deviceId, next);
      return HttpResponse.json(next);
    },
  ),
  http.delete(
    '/api/v1/me/push-registrations/:deviceId',
    ({ request, params }): Response => {
      const deviceId = String(params.deviceId);
      const current = registrations.get(deviceId) ?? {
        id: crypto.randomUUID(),
        version: 0,
        stateVersion: 0,
        fid: null,
      };
      const version = Number(
        new URL(request.url).searchParams.get('stateVersion'),
      );
      if (version !== current.stateVersion) {
        return conflict('STALE_PUSH_STATE', {
          currentStateVersion: current.stateVersion,
          enabled: current.fid !== null,
        });
      }
      const next = {
        ...current,
        fid: null,
        stateVersion: current.stateVersion + 1,
      };
      registrations.set(deviceId, next);
      return new HttpResponse(null, {
        status: 204,
        headers: { 'Push-State-Version': String(next.stateVersion) },
      });
    },
  ),
];
