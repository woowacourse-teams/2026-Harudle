/** @jest-environment node */
import {
  afterEach,
  beforeEach,
  describe,
  expect,
  it,
  jest,
} from '@jest/globals';
import { RequestError } from '../../shared/api';
import { setAccessToken } from '../../shared/auth';
import terms from '../../mocks/legal/terms-2026-10-22.json';
import privacy from '../../mocks/legal/privacy-2026-08-24.json';
import {
  getLegalDocument,
  getLegalDocuments,
  isLegalDocument,
  isLegalDocumentsResponse,
} from './legalDocuments';

beforeEach((): void => setAccessToken(null));
afterEach((): void => {
  jest.restoreAllMocks();
});

describe('공개 약관 문서 API 경계', (): void => {
  it('비로그인 상태에서 두 문서의 전문을 인증 갱신 없이 조회하고 취소 신호를 전달한다', async (): Promise<void> => {
    const response = { documents: [terms, privacy] };
    const fetch = jest
      .spyOn(globalThis, 'fetch')
      .mockResolvedValue(Response.json(response));
    const signal = new AbortController().signal;
    await expect(getLegalDocuments({ signal })).resolves.toEqual(response);
    expect(fetch).toHaveBeenCalledTimes(1);
    expect(fetch).toHaveBeenCalledWith('/api/v1/legal/documents', {
      signal,
      cache: 'no-store',
    });
  });
  it('지정한 종류와 버전의 전문을 조회한다', async (): Promise<void> => {
    const fetch = jest
      .spyOn(globalThis, 'fetch')
      .mockResolvedValue(Response.json(privacy));
    await expect(getLegalDocument('PRIVACY', privacy.version)).resolves.toEqual(
      privacy,
    );
    expect(fetch).toHaveBeenCalledWith(
      `/api/v1/legal/documents/privacy/${privacy.version}`,
      { signal: undefined, cache: 'no-store' },
    );
  });
  it('요청한 것과 다른 문서나 버전을 성공 응답으로 받아도 거절한다', async (): Promise<void> => {
    jest
      .spyOn(globalThis, 'fetch')
      .mockResolvedValueOnce(Response.json(privacy))
      .mockResolvedValueOnce(
        Response.json({ ...terms, version: '2026-01-01' }),
      );
    await expect(getLegalDocument('TERMS', terms.version)).rejects.toThrow(
      '문서 응답이 올바르지 않아요.',
    );
    await expect(getLegalDocument('TERMS', terms.version)).rejects.toThrow(
      '문서 응답이 올바르지 않아요.',
    );
  });
  it('Problem Details를 보존하고 JSON이 아닌 실패는 기본 오류로 안내한다', async (): Promise<void> => {
    const problem = {
      type: 'about:blank',
      title: 'Not found',
      status: 404 as const,
      detail: '문서를 찾을 수 없어요.',
      instance: '/api/v1/legal/documents/terms/old',
      code: 'LEGAL_DOCUMENT_NOT_FOUND',
    };
    jest
      .spyOn(globalThis, 'fetch')
      .mockResolvedValueOnce(Response.json(problem, { status: 404 }))
      .mockResolvedValueOnce(
        new Response('<html>error</html>', { status: 503 }),
      );
    await expect(getLegalDocument('TERMS', 'old')).rejects.toEqual(
      new RequestError(problem),
    );
    await expect(getLegalDocuments()).rejects.toThrow(
      '문서를 불러오지 못했어요.',
    );
  });
  it('경로를 바꿀 수 있는 버전은 요청 전에 거절한다', async (): Promise<void> => {
    const fetch = jest.spyOn(globalThis, 'fetch');
    await expect(
      getLegalDocument('TERMS', '../privacy/current'),
    ).rejects.toThrow('문서 버전이 올바르지 않아요.');
    expect(fetch).not.toHaveBeenCalled();
  });
  it.each([
    { ...terms, type: 'OTHER' },
    { ...terms, version: '' },
    { ...terms, title: ' ' },
    { ...terms, effectiveDate: '2026-02-30' },
    { ...terms, effectiveDate: 'not-a-date' },
    { ...terms, contentHash: 'not-a-hash' },
    { ...terms, sourceUrl: 'javascript:alert(1)' },
    { ...terms, sourceUrl: 'https://user:password@example.com/' },
    { ...terms, sections: [] },
    { ...terms, sections: [{ title: '안내', paragraphs: [null] }] },
  ])('전문과 메타데이터가 잘못되면 거절한다: %j', (value): void => {
    expect(isLegalDocument(value)).toBe(false);
  });
  it('한 종류가 없거나 중복된 카탈로그는 거절한다', (): void => {
    expect(isLegalDocument(terms)).toBe(true);
    expect(isLegalDocumentsResponse({ documents: [terms, privacy] })).toBe(
      true,
    );
    expect(isLegalDocumentsResponse({ documents: [] })).toBe(false);
    expect(isLegalDocumentsResponse({ documents: [terms] })).toBe(false);
    expect(isLegalDocumentsResponse({ documents: [terms, terms] })).toBe(false);
  });
});
