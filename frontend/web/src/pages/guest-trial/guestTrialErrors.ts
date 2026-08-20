import { RequestError } from '../../shared/api';

export const isGuestTrialAlreadyUsedError = (
  error: unknown,
): error is RequestError => {
  return (
    error instanceof RequestError &&
    error.problem.code === 'GUEST_TRIAL_ALREADY_USED'
  );
};
