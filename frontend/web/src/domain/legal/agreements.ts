import { API_BASE_URL, isProblemDetails, RequestError } from '../../shared/api';
import { authFetch } from '../../shared/auth';
import { isRecord } from '../../shared/utils';
import {
  isLegalDocumentReference,
  type LegalDocumentReference,
} from './legalDocuments';

export interface AgreementStatus {
  readonly minimumAge: 14;
  readonly enforcementEnabled: boolean;
  readonly requiresAcceptance: boolean;
  readonly terms: LegalDocumentReference;
  readonly privacyNotice: LegalDocumentReference;
  readonly acceptedAt: string | null;
}

export interface TermsAcceptanceRequest {
  readonly termsVersion: string;
  readonly termsContentHash: string;
  readonly privacyNoticeVersion: string;
  readonly privacyNoticeContentHash: string;
  readonly accepted: true;
  readonly ageConfirmed: true;
}

const isAcceptedAt = (value: unknown): value is string => {
  if (
    typeof value !== 'string' ||
    !/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d{1,9})?Z$/.test(value)
  )
    return false;
  const timestamp = Date.parse(value);
  return (
    Number.isFinite(timestamp) &&
    new Date(timestamp).toISOString().slice(0, 19) === value.slice(0, 19)
  );
};

export const isAgreementStatus = (value: unknown): value is AgreementStatus =>
  isRecord(value) &&
  value.minimumAge === 14 &&
  typeof value.enforcementEnabled === 'boolean' &&
  typeof value.requiresAcceptance === 'boolean' &&
  isLegalDocumentReference(value.terms) &&
  isLegalDocumentReference(value.privacyNotice) &&
  (value.acceptedAt === null || isAcceptedAt(value.acceptedAt)) &&
  (value.enforcementEnabled
    ? value.requiresAcceptance === (value.acceptedAt === null)
    : value.requiresAcceptance === false);

const readResponse = async (response: Response): Promise<AgreementStatus> => {
  const data: unknown = await response.json().catch((): null => null);
  if (!response.ok) {
    if (isProblemDetails(data)) throw new RequestError(data);
    throw new Error('이용 동의 정보를 확인하지 못했어요.');
  }
  if (!isAgreementStatus(data))
    throw new Error('이용 동의 응답이 올바르지 않아요.');
  return data;
};

export const getAgreementStatus = async ({
  signal,
}: { readonly signal?: AbortSignal } = {}): Promise<AgreementStatus> => {
  const response = await authFetch(`${API_BASE_URL}/me/agreements`, {
    signal,
    cache: 'no-store',
  });
  return readResponse(response);
};

export const acceptTerms = async (
  request: TermsAcceptanceRequest,
  { signal }: { readonly signal?: AbortSignal } = {},
): Promise<AgreementStatus> => {
  const response = await authFetch(`${API_BASE_URL}/me/agreements`, {
    method: 'PUT',
    signal,
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(request),
  });
  const status = await readResponse(response);
  if (
    status.requiresAcceptance ||
    status.acceptedAt === null ||
    status.terms.version !== request.termsVersion ||
    status.terms.contentHash !== request.termsContentHash ||
    status.privacyNotice.version !== request.privacyNoticeVersion ||
    status.privacyNotice.contentHash !== request.privacyNoticeContentHash
  ) {
    throw new Error('동의 저장 결과를 확인하지 못했어요. 다시 시도해 주세요.');
  }
  return status;
};
