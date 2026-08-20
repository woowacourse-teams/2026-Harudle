import { describe, expect, it } from '@jest/globals';
import { RequestError, type ProblemDetails } from '../../shared/api';
import { isGuestTrialAlreadyUsedError } from './guestTrialErrors';

const createProblem = (code: string): ProblemDetails => ({
  type: 'about:blank',
  title: 'Conflict',
  status: 409,
  detail: '게스트 체험을 이미 사용했습니다.',
  instance: '/api/v1/guest/diaries',
  code,
  traceId: 'trace-id',
});

describe('게스트 체험 오류', () => {
  it('이미 사용한 게스트 체험 오류를 구분한다', () => {
    expect(
      isGuestTrialAlreadyUsedError(
        new RequestError(createProblem('GUEST_TRIAL_ALREADY_USED')),
      ),
    ).toBe(true);
    expect(
      isGuestTrialAlreadyUsedError(
        new RequestError(createProblem('GENERATION_IN_PROGRESS')),
      ),
    ).toBe(false);
  });
});
