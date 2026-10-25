import { ERROR_MESSAGES } from '../../shared/errorMessage';
import { API_BASE_URL, isProblemDetails, RequestError } from '../../shared/api';

export const logout = async ({
  csrfToken,
}: {
  csrfToken: string;
}): Promise<void> => {
  const response = await fetch(`${API_BASE_URL}/auth/logout`, {
    method: 'POST',
    credentials: 'include',
    headers: {
      'X-XSRF-TOKEN': csrfToken,
    },
  });

  if (!response.ok) {
    const errorData: unknown = await response.json().catch(() => null);

    if (isProblemDetails(errorData)) {
      throw new RequestError(errorData);
    }

    throw new Error(ERROR_MESSAGES.LOGOUT_FAILED);
  }
};
