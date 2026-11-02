import { describe, expect, it } from '@jest/globals';
import { RequestError, type ProblemDetails } from '../../shared/api';
import {
  getWithdrawalErrorMessage,
  isWithdrawalResponse,
  WithdrawalResultUnknownError,
} from './withdrawal';

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

describe('회원 탈퇴 오류 안내', (): void => {
  const problem: ProblemDetails = {
    type: 'about:blank',
    title: 'Conflict',
    status: 409,
    detail: '실패했어요!',
    instance: '/api/v1/me/withdrawal',
    code: 'CONFLICT',
  };

  it('일기 생성 중에는 생성이 끝난 뒤 다시 시도하도록 안내한다', (): void => {
    expect(getWithdrawalErrorMessage(new RequestError(problem))).toBe(
      '일기 생성이 끝난 뒤 회원 탈퇴를 다시 시도해 주세요.',
    );
  });

  it('탈퇴 결과를 알 수 없으면 로그인해 계정 상태를 확인하도록 안내한다', (): void => {
    expect(getWithdrawalErrorMessage(new WithdrawalResultUnknownError())).toBe(
      '회원 탈퇴 결과를 확인하지 못했어요. 다시 로그인해 계정 상태를 확인해 주세요.',
    );
  });

  it('그 외 오류는 잠시 후 다시 시도하도록 안내한다', (): void => {
    expect(getWithdrawalErrorMessage(new Error())).toBe(
      '회원 탈퇴 요청을 처리하지 못했어요. 잠시 후 다시 시도해 주세요.',
    );
  });
});
