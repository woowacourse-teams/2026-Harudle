import { delay, http, HttpResponse } from 'msw';
import terms from './legal/terms-2026-10-22.json';
import privacy from './legal/privacy-2026-08-24.json';
import { MOCK_SCENARIO_HEADER, MOCK_SCENARIOS } from './mockScenarios';
import { isRecord } from '../shared/utils';

// 운영 화면은 API를 조회한다. 이 문서는 로컬 열람·브라우저 테스트용 사본이다.
const documents = [terms, privacy];

const unavailable = (request: Request): Response | null =>
  request.headers.get(MOCK_SCENARIO_HEADER) ===
  MOCK_SCENARIOS.legalDocumentsFailure
    ? HttpResponse.json(
        {
          type: 'about:blank',
          title: 'Legal documents unavailable',
          status: 503,
          detail: '문서를 불러오지 못했어요.',
          code: 'LEGAL_DOCUMENTS_UNAVAILABLE',
          instance: new URL(request.url).pathname,
        },
        { status: 503 },
      )
    : null;

export const legalHandlers = [
  http.get(
    '/api/v1/legal/documents',
    async ({ request }): Promise<Response> => {
      await delay(150);
      return unavailable(request) ?? HttpResponse.json({ documents });
    },
  ),
  http.get(
    '/api/v1/legal/documents/:type/:version',
    async ({ request, params }): Promise<Response> => {
      await delay(150);
      const type =
        params.type === 'terms'
          ? 'TERMS'
          : params.type === 'privacy'
            ? 'PRIVACY'
            : null;
      const document = documents.find(
        (item): boolean =>
          item.type === type && item.version === params.version,
      );
      return (
        unavailable(request) ??
        (document
          ? HttpResponse.json(document)
          : HttpResponse.json(
              {
                type: 'about:blank',
                title: 'Legal document not found',
                status: 404,
                detail: '문서를 찾을 수 없어요.',
                code: 'LEGAL_DOCUMENT_NOT_FOUND',
                instance: new URL(request.url).pathname,
              },
              { status: 404 },
            ))
      );
    },
  ),
];

const ACCEPTANCE_STORAGE_KEY = `harudle:mock:terms-acceptance:${terms.version}:${terms.contentHash}`;
const requiredScenarios = new Set<string>([
  MOCK_SCENARIOS.agreementRequired,
  MOCK_SCENARIOS.agreementMutationFailure,
  MOCK_SCENARIOS.agreementDocumentChanged,
  MOCK_SCENARIOS.agreementStatusFailure,
]);

const agreementProblem = (
  request: Request,
  status: 400 | 401 | 403 | 409 | 503,
  code: string,
  detail: string,
): Response =>
  HttpResponse.json(
    {
      type: 'about:blank',
      title: 'Agreement request failed',
      status,
      detail,
      code,
      instance: new URL(request.url).pathname,
    },
    { status },
  );

const currentAgreementStatus = (request: Request): object => {
  let acceptedAt: string | null = null;
  try {
    acceptedAt = sessionStorage.getItem(ACCEPTANCE_STORAGE_KEY);
  } catch {
    /* 테스트 저장소가 없으면 미동의 상태로 시작한다. */
  }
  const enforcementEnabled = requiredScenarios.has(
    request.headers.get(MOCK_SCENARIO_HEADER) ?? '',
  );
  return {
    minimumAge: 14,
    enforcementEnabled,
    requiresAcceptance: enforcementEnabled && acceptedAt === null,
    terms: { version: terms.version, contentHash: terms.contentHash },
    privacyNotice: {
      version: privacy.version,
      contentHash: privacy.contentHash,
    },
    acceptedAt,
  };
};

legalHandlers.push(
  http.get('/api/v1/me/agreements', async ({ request }): Promise<Response> => {
    await delay(150);
    if (request.headers.get('Authorization') !== 'Bearer mock-access-token')
      return agreementProblem(
        request,
        401,
        'AUTHENTICATION_REQUIRED',
        '로그인이 필요해요.',
      );
    if (
      request.headers.get(MOCK_SCENARIO_HEADER) ===
      MOCK_SCENARIOS.agreementStatusFailure
    )
      return agreementProblem(
        request,
        503,
        'AGREEMENT_STATUS_UNAVAILABLE',
        '이용 동의 정보를 불러오지 못했어요.',
      );
    return HttpResponse.json(currentAgreementStatus(request), {
      headers: { 'Cache-Control': 'no-store' },
    });
  }),
  http.put('/api/v1/me/agreements', async ({ request }): Promise<Response> => {
    await delay(500);
    if (request.headers.get('Authorization') !== 'Bearer mock-access-token')
      return agreementProblem(
        request,
        401,
        'AUTHENTICATION_REQUIRED',
        '로그인이 필요해요.',
      );
    const data: unknown = await request.json().catch((): null => null);
    if (!isRecord(data) || data.accepted !== true || data.ageConfirmed !== true)
      return agreementProblem(
        request,
        400,
        'INVALID_TERMS_ACCEPTANCE',
        '필수 항목을 확인해 주세요.',
      );
    if (
      data.termsVersion !== terms.version ||
      data.termsContentHash !== terms.contentHash ||
      data.privacyNoticeVersion !== privacy.version ||
      data.privacyNoticeContentHash !== privacy.contentHash ||
      request.headers.get(MOCK_SCENARIO_HEADER) ===
        MOCK_SCENARIOS.agreementDocumentChanged
    )
      return agreementProblem(
        request,
        409,
        'LEGAL_DOCUMENT_CHANGED',
        '문서가 바뀌었어요. 다시 확인해 주세요.',
      );
    if (
      request.headers.get(MOCK_SCENARIO_HEADER) ===
      MOCK_SCENARIOS.agreementMutationFailure
    )
      return agreementProblem(
        request,
        503,
        'AGREEMENT_STORAGE_UNAVAILABLE',
        '동의를 저장하지 못했어요.',
      );
    try {
      if (sessionStorage.getItem(ACCEPTANCE_STORAGE_KEY) === null)
        sessionStorage.setItem(
          ACCEPTANCE_STORAGE_KEY,
          new Date().toISOString(),
        );
    } catch {
      return agreementProblem(
        request,
        503,
        'AGREEMENT_STORAGE_UNAVAILABLE',
        '동의를 저장하지 못했어요.',
      );
    }
    return HttpResponse.json(currentAgreementStatus(request), {
      headers: { 'Cache-Control': 'no-store' },
    });
  }),
);
