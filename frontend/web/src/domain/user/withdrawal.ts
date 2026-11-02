import { API_BASE_URL, isProblemDetails, RequestError } from '../../shared/api';
import { authFetch } from '../../shared/auth';

interface WithdrawalCompleted {
  readonly status: 'COMPLETED';
  readonly authorizationUrl: null;
  readonly appleManualUnlinkRequired: boolean;
}

interface WithdrawalAuthenticationRequired {
  readonly status: 'AUTHENTICATION_REQUIRED';
  readonly authorizationUrl: string;
  readonly appleManualUnlinkRequired: false;
}

export type WithdrawalResponse =
  WithdrawalCompleted | WithdrawalAuthenticationRequired;

export const isWithdrawalResponse = (
  value: unknown,
): value is WithdrawalResponse => {
  if (typeof value !== 'object' || value === null) return false;
  if (!('status' in value) || !('authorizationUrl' in value)) return false;
  if (!('appleManualUnlinkRequired' in value)) return false;

  if (value.status === 'COMPLETED') {
    return (
      value.authorizationUrl === null &&
      typeof value.appleManualUnlinkRequired === 'boolean'
    );
  }

  return (
    value.status === 'AUTHENTICATION_REQUIRED' &&
    typeof value.authorizationUrl === 'string' &&
    /^\/oauth2\/authorization\/google\?withdrawal=[0-9a-f-]+$/i.test(
      value.authorizationUrl,
    ) &&
    value.appleManualUnlinkRequired === false
  );
};

export const requestWithdrawal = async (): Promise<WithdrawalResponse> => {
  const response = await authFetch(`${API_BASE_URL}/me/withdrawal`, {
    method: 'POST',
    credentials: 'include',
  });

  if (!response.ok) {
    const errorData: unknown = await response.json().catch(() => null);
    if (isProblemDetails(errorData)) throw new RequestError(errorData);
    throw new Error('실패했어요!');
  }

  const data: unknown = await response.json();
  if (!isWithdrawalResponse(data)) throw new Error('실패했어요!');
  return data;
};
