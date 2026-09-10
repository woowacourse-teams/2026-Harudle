import { API_BASE_URL, isProblemDetails, RequestError } from '../../shared/api';
import { authFetch } from '../../shared/auth';

export const logout = async ({
  csrfToken,
}: {
  csrfToken: string;
}): Promise<void> => {
  const response = await authFetch(`${API_BASE_URL}/auth/logout`, {
    method: 'POST',
    headers: {
      'X-XSRF-TOKEN': csrfToken,
    },
  });

  if (!response.ok) {
    const errorData: unknown = await response.json().catch(() => null);

    if (isProblemDetails(errorData)) {
      throw new RequestError(errorData);
    }

    throw new Error('로그아웃하는 중 문제가 발생했습니다. 다시 시도해주세요.');
  }
};
