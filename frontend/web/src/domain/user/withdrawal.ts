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

const GENERATION_IN_PROGRESS =
  '일기 생성이 끝난 뒤 회원 탈퇴를 다시 시도해 주세요.';
const WITHDRAWAL_FAILED =
  '회원 탈퇴 요청을 처리하지 못했어요. 잠시 후 다시 시도해 주세요.';
const WITHDRAWAL_RESULT_UNKNOWN =
  '회원 탈퇴 결과를 확인하지 못했어요. 다시 로그인해 계정 상태를 확인해 주세요.';

export class WithdrawalResultUnknownError extends Error {
  constructor() {
    super(WITHDRAWAL_RESULT_UNKNOWN);
  }
}

export const getWithdrawalErrorMessage = (error: unknown): string => {
  if (
    error instanceof WithdrawalResultUnknownError ||
    error instanceof TypeError
  ) {
    return WITHDRAWAL_RESULT_UNKNOWN;
  }
  if (error instanceof RequestError) {
    if (error.problem.status === 409) return GENERATION_IN_PROGRESS;
    if (error.problem.status === 401) {
      return '로그인 정보가 만료됐어요. 다시 로그인한 뒤 시도해 주세요.';
    }
    return WITHDRAWAL_FAILED;
  }
  if (error instanceof Error && error.message === GENERATION_IN_PROGRESS) {
    return GENERATION_IN_PROGRESS;
  }
  return WITHDRAWAL_FAILED;
};

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
  let response: Response;
  try {
    response = await authFetch(`${API_BASE_URL}/me/withdrawal`, {
      method: 'POST',
      credentials: 'include',
    });
  } catch (error: unknown) {
    if (error instanceof TypeError) throw new WithdrawalResultUnknownError();
    throw error;
  }

  if (!response.ok) {
    const errorData: unknown = await response.json().catch(() => null);
    if (isProblemDetails(errorData)) throw new RequestError(errorData);
    if (response.status === 409) throw new Error(GENERATION_IN_PROGRESS);
    throw new Error(WITHDRAWAL_FAILED);
  }

  const data: unknown = await response.json().catch(() => null);
  if (!isWithdrawalResponse(data)) throw new WithdrawalResultUnknownError();
  return data;
};
