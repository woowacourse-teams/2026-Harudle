import { delay, http, HttpResponse } from 'msw';
import { isRecord } from '../shared/utils';
import { MOCK_CSRF_TOKEN, markMockSessionLoggedIn } from './mockAuthSession';
import { MOCK_SCENARIO_HEADER, MOCK_SCENARIOS } from './mockScenarios';

interface PendingNonce {
  readonly value: string;
  readonly expiresAt: number;
}

const NONCE_KEY = 'harudle:mock:apple-native-nonce:v1';
const NONCE_TTL_MS = 5 * 60 * 1_000;
let fallbackNonce: PendingNonce | null = null;

const saveNonce = (nonce: PendingNonce | null): void => {
  fallbackNonce = nonce;
  try {
    if (nonce) sessionStorage.setItem(NONCE_KEY, JSON.stringify(nonce));
    else sessionStorage.removeItem(NONCE_KEY);
  } catch {
    /* 저장소가 없는 테스트에서는 메모리 상태를 사용한다. */
  }
};

const consumeNonce = (): PendingNonce | null => {
  let nonce = fallbackNonce;
  try {
    const stored = sessionStorage.getItem(NONCE_KEY);
    const parsed: unknown = stored ? JSON.parse(stored) : null;
    nonce =
      isRecord(parsed) &&
      typeof parsed.value === 'string' &&
      typeof parsed.expiresAt === 'number'
        ? { value: parsed.value, expiresAt: parsed.expiresAt }
        : null;
  } catch {
    /* 저장소가 없는 테스트에서는 메모리 상태를 사용한다. */
  }
  saveNonce(null);
  return nonce;
};

export const resetAppleNativeAuthMockState = (): void => saveNonce(null);

// PR #504의 컨트롤러 오류는 Problem Details 없이 빈 본문으로 반환된다.
const emptyResponse = (status: number): Response =>
  new HttpResponse(null, {
    status,
    headers: { 'Cache-Control': 'no-store' },
  });

export const appleNativeAuthHandlers = [
  http.post(
    '/api/v1/auth/apple/native/nonce',
    async ({ request }): Promise<Response> => {
      await delay(300);
      if (request.headers.get('X-XSRF-TOKEN') !== MOCK_CSRF_TOKEN)
        return emptyResponse(403);

      const bytes = crypto.getRandomValues(new Uint8Array(32));
      const value = btoa(String.fromCharCode(...bytes))
        .replaceAll('+', '-')
        .replaceAll('/', '_')
        .replaceAll('=', '');
      saveNonce({ value, expiresAt: Date.now() + NONCE_TTL_MS });
      return HttpResponse.json(
        { nonce: value },
        { headers: { 'Cache-Control': 'no-store' } },
      );
    },
  ),
  http.post(
    '/api/v1/auth/apple/native/login',
    async ({ request }): Promise<Response> => {
      await delay(300);
      if (request.headers.get('X-XSRF-TOKEN') !== MOCK_CSRF_TOKEN)
        return emptyResponse(403);

      let body: unknown;
      try {
        body = await request.json();
      } catch {
        return emptyResponse(400);
      }
      if (
        !isRecord(body) ||
        typeof body.authorizationCode !== 'string' ||
        !body.authorizationCode.trim() ||
        body.authorizationCode.length > 4096 ||
        typeof body.identityToken !== 'string' ||
        !body.identityToken.trim() ||
        body.identityToken.length > 16384
      ) {
        return emptyResponse(400);
      }

      // 백엔드와 같이 본문 검증 이후 nonce를 소모한다. 검증 실패 시에도 복구하지 않는다.
      const pending = consumeNonce();
      if (!pending || Date.now() >= pending.expiresAt)
        return emptyResponse(400);

      // Apple 서명·토큰 교환은 생략하고 검증 결과를 시나리오로 재현한다.
      const scenario = request.headers.get(MOCK_SCENARIO_HEADER);
      if (scenario === MOCK_SCENARIOS.appleNativeInvalidCredentials)
        return emptyResponse(400);
      if (scenario === MOCK_SCENARIOS.appleNativeInactiveUser)
        return emptyResponse(401);
      if (scenario === MOCK_SCENARIOS.appleNativeProviderFailure)
        return emptyResponse(502);

      // HttpOnly 쿠키 대신 기존 Mock 세션 상태로 refresh 성공을 재현한다.
      markMockSessionLoggedIn();
      return emptyResponse(204);
    },
  ),
];
