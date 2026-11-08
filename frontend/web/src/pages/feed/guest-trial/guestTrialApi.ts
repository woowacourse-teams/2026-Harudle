import {
  API_BASE_URL,
  isProblemDetails,
  RequestError,
} from '../../../shared/api';
import { authFetch, requestCsrfToken } from '../../../shared/auth';
import {
  isDiaryGeneration,
  toGenerationFailureCode,
  type DiaryGeneration,
} from '../../../domain/diary/diaryGeneration';
import {
  isDiaryGenerationStatusResponse,
  type DiaryGenerationStatusResponse,
} from '../../../domain/diary/diaryGenerate';
import {
  getDiaryDetail,
  type DiaryDetailResponse,
} from '../../../domain/diary/diaryDetail';
import { isCanonicalUuid } from '../../landing/guestTrialUuid';
import type { GuestDiaryRequest } from '../../landing/guestTrialApi';
import { GUEST_TRIAL_COPY } from './copy';

export {
  getGuestDiary as getGuestTrialDiary,
  issueGuestSession,
  type GuestDiaryResponse as GuestTrialDiary,
} from '../../landing/guestTrialApi';

export interface AcceptedGuestTrialDiary {
  diaryId: string;
  generationId: string;
}

interface GuestDiaryAcceptedResponse {
  id: string;
  generation: DiaryGeneration;
}

export interface GuestDiaryClaimResponse {
  diaryId: string;
  diaryDate: string;
  generationStatus: 'PENDING' | 'PROCESSING' | 'SUCCEEDED';
  claimedAt: string;
}

const isRecord = (value: unknown): value is Record<string, unknown> => {
  return typeof value === 'object' && value !== null;
};

const isGuestDiaryAcceptedResponse = (
  value: unknown,
): value is GuestDiaryAcceptedResponse => {
  return (
    isRecord(value) &&
    isCanonicalUuid(value.id) &&
    isDiaryGeneration(value.generation) &&
    isCanonicalUuid(value.generation.id)
  );
};

export const isGuestDiaryClaimResponse = (
  value: unknown,
): value is GuestDiaryClaimResponse => {
  return (
    isRecord(value) &&
    isCanonicalUuid(value.diaryId) &&
    typeof value.diaryDate === 'string' &&
    (value.generationStatus === 'PENDING' ||
      value.generationStatus === 'PROCESSING' ||
      value.generationStatus === 'SUCCEEDED') &&
    typeof value.claimedAt === 'string'
  );
};

const throwRequestError = async (
  response: Response,
  fallbackMessage: string,
): Promise<never> => {
  const data: unknown = await response.json().catch(() => null);

  if (isProblemDetails(data)) throw new RequestError(data);
  throw new Error(fallbackMessage);
};

/** 접수(202)·멱등 재요청(200) 응답을 기다리지 않고 바로 돌려준다. */
export const submitGuestTrialDiary = async ({
  request,
  idempotencyKey,
}: {
  request: GuestDiaryRequest;
  idempotencyKey: string;
}): Promise<AcceptedGuestTrialDiary> => {
  const csrfToken = await requestCsrfToken();
  const response = await fetch(`${API_BASE_URL}/guest/diaries`, {
    method: 'POST',
    credentials: 'include',
    headers: {
      'Content-Type': 'application/json',
      'Idempotency-Key': idempotencyKey,
      'X-XSRF-TOKEN': csrfToken,
    },
    body: JSON.stringify(request),
  });

  if (!response.ok) {
    return throwRequestError(response, GUEST_TRIAL_COPY.submitFailed);
  }

  const data: unknown = await response.json();
  if (!isGuestDiaryAcceptedResponse(data)) {
    throw new Error(GUEST_TRIAL_COPY.submitFailed);
  }

  return { diaryId: data.id, generationId: data.generation.id };
};

export const getGuestTrialGenerationStatus = async (
  generationId: string,
): Promise<DiaryGenerationStatusResponse> => {
  const response = await fetch(
    `${API_BASE_URL}/guest/diary-generations/${generationId}`,
    { credentials: 'include', cache: 'no-store' },
  );

  if (!response.ok) {
    return throwRequestError(response, GUEST_TRIAL_COPY.statusCheckFailed);
  }

  const data: unknown = await response.json();
  if (!isDiaryGenerationStatusResponse(data)) {
    throw new Error(GUEST_TRIAL_COPY.statusCheckFailed);
  }

  if (data.status === 'FAILED') {
    return { ...data, errorCode: toGenerationFailureCode(data.errorCode) };
  }

  return data;
};

const requestClaim = async (csrfToken: string): Promise<Response> => {
  return authFetch(`${API_BASE_URL}/guest/diary-claim`, {
    method: 'POST',
    credentials: 'include',
    headers: { 'X-XSRF-TOKEN': csrfToken },
  });
};

/** 대상 일기는 guest_session 쿠키로 서버가 정한다. CSRF 오류만 한 번 다시 시도한다. */
export const claimGuestTrialDiary =
  async (): Promise<GuestDiaryClaimResponse> => {
    let response = await requestClaim(await requestCsrfToken());

    if (response.status === 403) {
      const data: unknown = await response
        .clone()
        .json()
        .catch(() => null);
      if (isProblemDetails(data) && data.code === 'INVALID_CSRF_TOKEN') {
        response = await requestClaim(await requestCsrfToken());
      }
    }

    if (!response.ok) {
      return throwRequestError(response, GUEST_TRIAL_COPY.claimFailed);
    }

    const data: unknown = await response.json();
    if (!isGuestDiaryClaimResponse(data)) {
      throw new Error(GUEST_TRIAL_COPY.claimFailed);
    }

    return data;
  };

export type GuestTrialClaimOutcome =
  | { type: 'claimed'; diary: DiaryDetailResponse }
  | { type: 'retryable'; errorCode: string }
  | { type: 'reauth'; errorCode: string }
  | { type: 'terms'; errorCode: string }
  // 가져올 게스트 일기가 없다는 404. 백엔드 안내대로 사용자에게 알리지 않고 넘어간다.
  | { type: 'ignored'; errorCode: string }
  | {
      type: 'terminal';
      reason: 'alreadyClaimed' | 'limitExceeded' | 'forbidden';
      errorCode: string;
      message: string;
    };

const NETWORK_ERROR = 'NETWORK_ERROR';

/** 저장한 ID가 이미 회원 소유인지 확인한다. 소유가 아니면 missing 결과를 돌려준다. */
const confirmMemberDiary = async (
  diaryId: string,
  missing: GuestTrialClaimOutcome,
  unavailableErrorCode = NETWORK_ERROR,
): Promise<GuestTrialClaimOutcome> => {
  try {
    return { type: 'claimed', diary: await getDiaryDetail({ diaryId }) };
  } catch (error: unknown) {
    if (
      error instanceof RequestError &&
      (error.problem.status === 404 || error.problem.status === 403)
    ) {
      return missing;
    }
    return {
      type: 'retryable',
      errorCode:
        error instanceof RequestError
          ? error.problem.code
          : unavailableErrorCode,
    };
  }
};

export const resolveGuestTrialClaim = async (
  storedDiaryId: string,
): Promise<GuestTrialClaimOutcome> => {
  let claim: GuestDiaryClaimResponse;

  try {
    claim = await claimGuestTrialDiary();
  } catch (error: unknown) {
    if (!(error instanceof RequestError)) {
      // 응답을 잃었어도 서버가 이미 옮겼을 수 있으므로 회원 소유를 먼저 확인한다.
      return confirmMemberDiary(storedDiaryId, {
        type: 'retryable',
        errorCode: NETWORK_ERROR,
      });
    }

    const { code, status, detail } = error.problem;
    if (status === 404) return { type: 'ignored', errorCode: code };

    switch (code) {
      case 'GUEST_DIARY_ALREADY_CLAIMED':
        return {
          type: 'terminal',
          reason: 'alreadyClaimed',
          errorCode: code,
          message: GUEST_TRIAL_COPY.claimAlreadyClaimed,
        };
      case 'GUEST_DIARY_CLAIM_LIMIT_EXCEEDED':
        return {
          type: 'terminal',
          reason: 'limitExceeded',
          errorCode: code,
          message: GUEST_TRIAL_COPY.claimLimitExceeded,
        };
      case 'TERMS_ACCEPTANCE_REQUIRED':
        return { type: 'terms', errorCode: code };
      case 'INVALID_CSRF_TOKEN':
        return { type: 'retryable', errorCode: code };
    }

    if (status === 401 || code === 'INVALID_REFRESH_TOKEN') {
      return { type: 'reauth', errorCode: code };
    }
    if (status === 403) {
      return {
        type: 'terminal',
        reason: 'forbidden',
        errorCode: code,
        message: detail,
      };
    }
    return { type: 'retryable', errorCode: code };
  }

  // 진행 중 응답은 완성으로 보지 않는다. 회원 상세 조회가 SUCCEEDED만 통과시킨다.
  const unavailableErrorCode =
    claim.generationStatus === 'SUCCEEDED'
      ? NETWORK_ERROR
      : `CLAIMED_${claim.generationStatus}`;
  return confirmMemberDiary(
    claim.diaryId,
    { type: 'retryable', errorCode: unavailableErrorCode },
    unavailableErrorCode,
  );
};
