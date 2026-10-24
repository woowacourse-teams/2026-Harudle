import { useRef, useState } from 'react';
import {
  getUserProfile,
  updateUserProfile,
  ProfileSaveResultUnknownError,
  type ProfileChanges,
  type ProfileResponse,
} from '../../domain/user/profile';
import { RequestError } from '../../shared/api';
import { useAnalytics } from '../../posthog/useAnalytics';
import {
  EMPTY_PROFILE_ERRORS,
  getProfileErrors,
  type ProfileErrors,
  type ProfileErrorField,
} from './profileErrors';

type ReloadReason = 'saved' | 'unknown' | 'unavailable';
type ProfileUpdateRequest =
  | { readonly status: 'idle' | 'saving' | 'refreshing' }
  | { readonly status: 'reload-required'; readonly reason: ReloadReason };
export interface SavedProfile {
  readonly profile: ProfileResponse;
  readonly saved: boolean;
}
interface UseProfileUpdateResult {
  readonly request: ProfileUpdateRequest;
  readonly errors: ProfileErrors;
  readonly saveProfile: (
    changes: ProfileChanges,
  ) => Promise<SavedProfile | null>;
  readonly retryProfileFetch: () => Promise<SavedProfile | null>;
  readonly isPending: () => boolean;
  readonly clearFieldError: (field: ProfileErrorField) => void;
  readonly setFieldError: (field: ProfileErrorField, message: string) => void;
  readonly reset: () => void;
}

const useProfileUpdate = (): UseProfileUpdateResult => {
  const { updateNickname } = useAnalytics();
  const pending = useRef(false);
  const reloadReason = useRef<ReloadReason | null>(null);
  const [request, setRequest] = useState<ProfileUpdateRequest>({
    status: 'idle',
  });
  const [errors, setErrors] = useState<ProfileErrors>(EMPTY_PROFILE_ERRORS);

  const fetchProfile = async (
    reason: ReloadReason,
  ): Promise<SavedProfile | null> => {
    setRequest({ status: 'refreshing' });
    try {
      const profile = await getUserProfile();
      updateNickname(profile.nickname);
      reloadReason.current = null;
      setRequest({ status: 'idle' });
      return { profile, saved: reason === 'saved' };
    } catch {
      reloadReason.current = reason;
      setRequest({ status: 'reload-required', reason });
      return null;
    }
  };

  const saveProfile = async (
    changes: ProfileChanges,
  ): Promise<SavedProfile | null> => {
    if (pending.current || reloadReason.current) return null;
    pending.current = true;
    setErrors(EMPTY_PROFILE_ERRORS);
    setRequest({ status: 'saving' });
    try {
      try {
        await updateUserProfile(changes);
      } catch (error: unknown) {
        if (error instanceof ProfileSaveResultUnknownError) {
          reloadReason.current = 'unknown';
        } else if (
          error instanceof RequestError &&
          error.problem.code === 'PUBLIC_PROFILE_NOT_ASSIGNED'
        ) {
          reloadReason.current = 'unavailable';
        } else {
          setErrors(getProfileErrors(error));
          setRequest({ status: 'idle' });
          return null;
        }
        setRequest({ status: 'reload-required', reason: reloadReason.current });
        return null;
      }
      return await fetchProfile('saved');
    } finally {
      pending.current = false;
    }
  };

  const retryProfileFetch = async (): Promise<SavedProfile | null> => {
    const reason = reloadReason.current;
    if (pending.current || !reason) return null;
    pending.current = true;
    try {
      return await fetchProfile(reason);
    } finally {
      pending.current = false;
    }
  };

  const setFieldError = (field: ProfileErrorField, message: string): void => {
    setErrors((previous): ProfileErrors => ({ ...previous, [field]: message }));
  };
  const clearFieldError = (field: ProfileErrorField): void =>
    setFieldError(field, '');
  const reset = (): void => {
    if (pending.current || reloadReason.current) return;
    setErrors(EMPTY_PROFILE_ERRORS);
    setRequest({ status: 'idle' });
  };
  const isPending = (): boolean => pending.current;
  return {
    request,
    errors,
    saveProfile,
    retryProfileFetch,
    isPending,
    clearFieldError,
    setFieldError,
    reset,
  };
};
export default useProfileUpdate;
