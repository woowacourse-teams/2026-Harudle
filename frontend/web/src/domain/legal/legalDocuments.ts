import { API_BASE_URL, isProblemDetails, RequestError } from '../../shared/api';
import { publicFetch } from '../../shared/auth';
import { isRecord } from '../../shared/utils';

export type LegalDocumentType = 'TERMS' | 'PRIVACY';

export interface LegalDocumentSection {
  readonly title: string;
  readonly paragraphs: readonly string[];
}

export interface LegalDocumentReference {
  readonly version: string;
  readonly contentHash: string;
}

export interface LegalDocument extends LegalDocumentReference {
  readonly type: LegalDocumentType;
  readonly title: string;
  readonly effectiveDate: string;
  readonly sourceUrl: string;
  readonly sections: readonly LegalDocumentSection[];
}

export interface LegalDocumentsResponse {
  readonly documents: readonly LegalDocument[];
}

const documentPaths: Record<LegalDocumentType, string> = {
  TERMS: 'terms',
  PRIVACY: 'privacy',
};

const isNonEmptyString = (value: unknown): value is string =>
  typeof value === 'string' && value.trim().length > 0;

const isVersion = (value: unknown): value is string =>
  typeof value === 'string' && /^[a-zA-Z0-9][a-zA-Z0-9._-]{0,63}$/.test(value);

const isEffectiveDate = (value: unknown): value is string => {
  if (typeof value !== 'string' || !/^\d{4}-\d{2}-\d{2}$/.test(value))
    return false;
  const timestamp = Date.parse(`${value}T00:00:00Z`);
  return (
    Number.isFinite(timestamp) &&
    new Date(timestamp).toISOString().slice(0, 10) === value
  );
};

const isSourceUrl = (value: unknown): value is string => {
  if (typeof value !== 'string') return false;
  try {
    const url = new URL(value);
    return (
      url.protocol === 'https:' && url.username === '' && url.password === ''
    );
  } catch {
    return false;
  }
};

const isLegalDocumentSection = (
  value: unknown,
): value is LegalDocumentSection =>
  isRecord(value) &&
  isNonEmptyString(value.title) &&
  Array.isArray(value.paragraphs) &&
  value.paragraphs.length > 0 &&
  value.paragraphs.every(isNonEmptyString);

export const isLegalDocumentReference = (
  value: unknown,
): value is LegalDocumentReference =>
  isRecord(value) &&
  isVersion(value.version) &&
  typeof value.contentHash === 'string' &&
  /^[a-f0-9]{64}$/.test(value.contentHash);

export const isLegalDocument = (value: unknown): value is LegalDocument =>
  isRecord(value) &&
  (value.type === 'TERMS' || value.type === 'PRIVACY') &&
  isLegalDocumentReference(value) &&
  isNonEmptyString(value.title) &&
  isEffectiveDate(value.effectiveDate) &&
  isSourceUrl(value.sourceUrl) &&
  Array.isArray(value.sections) &&
  value.sections.length > 0 &&
  value.sections.every(isLegalDocumentSection);

export const isLegalDocumentsResponse = (
  value: unknown,
): value is LegalDocumentsResponse =>
  isRecord(value) &&
  Array.isArray(value.documents) &&
  value.documents.length === 2 &&
  value.documents.every(isLegalDocument) &&
  new Set(
    value.documents.map(
      (document: LegalDocument): LegalDocumentType => document.type,
    ),
  ).size === 2;

const readResponse = async (response: Response): Promise<unknown> => {
  const data: unknown = await response.json().catch((): null => null);
  if (!response.ok) {
    if (isProblemDetails(data)) throw new RequestError(data);
    throw new Error('문서를 불러오지 못했어요.');
  }
  return data;
};

export const getLegalDocuments = async ({
  signal,
}: { readonly signal?: AbortSignal } = {}): Promise<LegalDocumentsResponse> => {
  const response = await publicFetch(`${API_BASE_URL}/legal/documents`, {
    signal,
    cache: 'no-store',
  });
  const data = await readResponse(response);
  if (!isLegalDocumentsResponse(data))
    throw new Error('문서 응답이 올바르지 않아요.');
  return data;
};

export const getLegalDocument = async (
  type: LegalDocumentType,
  version: string,
  { signal }: { readonly signal?: AbortSignal } = {},
): Promise<LegalDocument> => {
  if (!isVersion(version)) throw new Error('문서 버전이 올바르지 않아요.');
  const response = await publicFetch(
    `${API_BASE_URL}/legal/documents/${documentPaths[type]}/${encodeURIComponent(version)}`,
    { signal, cache: 'no-store' },
  );
  const data = await readResponse(response);
  if (!isLegalDocument(data) || data.type !== type || data.version !== version)
    throw new Error('문서 응답이 올바르지 않아요.');
  return data;
};
