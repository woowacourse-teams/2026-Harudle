import { ERROR_MESSAGES } from '../../shared/errorMessage';
import { RequestError } from '../../shared/api';

export class GuestTrialAlreadyUsedError extends Error {
  constructor() {
    super(ERROR_MESSAGES.GUEST_TRIAL_ALREADY_USED);
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
