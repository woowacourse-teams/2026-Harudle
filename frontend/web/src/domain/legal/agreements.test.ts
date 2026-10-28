/** @jest-environment node */
import {
  afterEach,
  beforeEach,
  describe,
  expect,
  it,
  jest,
} from '@jest/globals';
import { authFetch } from '../../shared/auth';
import { RequestError } from '../../shared/api';
import {
  acceptTerms,
  getAgreementStatus,
  isAgreementStatus,
  type AgreementStatus,
  type TermsAcceptanceRequest,
} from './agreements';
import terms from '../../mocks/legal/terms-2026-10-22.json';
import privacy from '../../mocks/legal/privacy-2026-08-24.json';

const mockAuthFetch = jest.fn<typeof authFetch>();
jest.mock('../../shared/auth', () => ({
  authFetch: (...args: Parameters<typeof authFetch>): Promise<Response> =>
    mockAuthFetch(...args),
}));
const status: AgreementStatus = {
  minimumAge: 14,
  enforcementEnabled: true,
  requiresAcceptance: false,
  terms: { version: terms.version, contentHash: terms.contentHash },
  privacyNotice: { version: privacy.version, contentHash: privacy.contentHash },
  acceptedAt: '2026-10-25T08:00:00Z',
};
const request: TermsAcceptanceRequest = {
  termsVersion: terms.version,
  termsContentHash: terms.contentHash,
  privacyNoticeVersion: privacy.version,
  privacyNoticeContentHash: privacy.contentHash,
  accepted: true,
  ageConfirmed: true,
};
beforeEach((): void => {
  mockAuthFetch.mockReset();
});
afterEach((): void => {
  jest.restoreAllMocks();
});

describe('회원 이용 동의 API', (): void => {
  it('상태 조회에 기존 인증 요청과 취소 신호를 사용한다', async (): Promise<void> => {
    mockAuthFetch.mockResolvedValueOnce(Response.json(status));
    const signal = new AbortController().signal;
    await expect(getAgreementStatus({ signal })).resolves.toEqual(status);
    expect(mockAuthFetch).toHaveBeenCalledWith('/api/v1/me/agreements', {
      signal,
      cache: 'no-store',
    });
  });
  it('사용자가 확인한 두 문서의 참조와 약관·연령 자기확인을 저장한다', async (): Promise<void> => {
    mockAuthFetch.mockResolvedValueOnce(Response.json(status));
    await expect(acceptTerms(request)).resolves.toEqual(status);
    expect(mockAuthFetch).toHaveBeenCalledWith('/api/v1/me/agreements', {
      method: 'PUT',
      signal: undefined,
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(request),
    });
  });
  it.each([409, 503] as const)(
    '문서 충돌·서버 오류는 임의 재시도하지 않는다: %s',
    async (statusCode): Promise<void> => {
      mockAuthFetch.mockResolvedValueOnce(
        Response.json(
          {
            type: 'about:blank',
            title: 'Failed',
            status: statusCode,
            detail: '요청을 다시 확인해 주세요.',
            code:
              statusCode === 409
                ? 'LEGAL_DOCUMENT_CHANGED'
                : 'AGREEMENT_STORAGE_UNAVAILABLE',
            instance: '/api/v1/me/agreements',
          },
          { status: statusCode },
        ),
      );
      await expect(acceptTerms(request)).rejects.toBeInstanceOf(RequestError);
      expect(mockAuthFetch).toHaveBeenCalledTimes(1);
    },
  );
  it('200이어도 저장 시각이 없거나 다른 문서의 기록이면 완료로 안내하지 않는다', async (): Promise<void> => {
    mockAuthFetch
      .mockResolvedValueOnce(
        Response.json({
          ...status,
          enforcementEnabled: false,
          acceptedAt: null,
        }),
      )
      .mockResolvedValueOnce(
        Response.json({
          ...status,
          terms: { ...status.terms, version: 'old' },
        }),
      );
    await expect(acceptTerms(request)).rejects.toThrow(
      '동의 저장 결과를 확인하지 못했어요.',
    );
    await expect(acceptTerms(request)).rejects.toThrow(
      '동의 저장 결과를 확인하지 못했어요.',
    );
  });
  it.each([
    { ...status, minimumAge: undefined },
    { ...status, minimumAge: 15 },
    { ...status, acceptedAt: '2026-02-30T08:00:00Z' },
    { ...status, terms: {} },
    { ...status, requiresAcceptance: true },
    { ...status, acceptedAt: null },
    {
      ...status,
      enforcementEnabled: false,
      requiresAcceptance: true,
      acceptedAt: null,
    },
  ])('미완성·상충된 상태 응답을 거절한다: %j', (value): void => {
    expect(isAgreementStatus(value)).toBe(false);
  });
});
