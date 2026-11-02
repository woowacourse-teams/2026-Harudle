import { describe, expect, it } from '@jest/globals';
import { isWithdrawalResponse } from './withdrawal';

describe('회원 탈퇴 응답 확인', (): void => {
  it.each([
    {
      status: 'COMPLETED',
      authorizationUrl: null,
      appleManualUnlinkRequired: false,
    },
    {
      status: 'COMPLETED',
      authorizationUrl: null,
      appleManualUnlinkRequired: true,
    },
    {
      status: 'AUTHENTICATION_REQUIRED',
      authorizationUrl:
        '/oauth2/authorization/google?withdrawal=12345678-1234-1234-1234-123456789abc',
      appleManualUnlinkRequired: false,
    },
  ])('정상 응답을 허용한다: $status', (value): void => {
    expect(isWithdrawalResponse(value)).toBe(true);
  });

  it.each([
    null,
    { status: 'COMPLETED', authorizationUrl: null },
    {
      status: 'COMPLETED',
      authorizationUrl: '/unexpected',
      appleManualUnlinkRequired: false,
    },
    {
      status: 'AUTHENTICATION_REQUIRED',
      authorizationUrl: 'https://example.com',
      appleManualUnlinkRequired: false,
    },
    {
      status: 'AUTHENTICATION_REQUIRED',
      authorizationUrl: '/oauth2/authorization/google?withdrawal=abc',
      appleManualUnlinkRequired: true,
    },
  ])('잘못된 응답은 거절한다', (value): void => {
    expect(isWithdrawalResponse(value)).toBe(false);
  });
});
