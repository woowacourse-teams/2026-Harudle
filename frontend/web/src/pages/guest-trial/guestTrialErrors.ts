import { RequestError } from '../../shared/api';

export class GuestTrialAlreadyUsedError extends Error {
  constructor() {
    super('게스트 체험을 이미 사용했습니다');
    this.name = 'GuestTrialAlreadyUsedError';
  }
}

export const isGuestTrialAlreadyUsedError = (error: unknown): boolean => {
  return (
    error instanceof GuestTrialAlreadyUsedError ||
    (error instanceof RequestError &&
      error.problem.code === 'GUEST_TRIAL_ALREADY_USED')
  );
};
