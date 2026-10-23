import { useState } from 'react';
import {
  getUserProfile,
  updateUserProfile,
  ProfileSaveResultUnknownError,
  type ProfileChanges,
  type ProfileResponse,
} from '../../domain/user/profile';
import { RequestError } from '../../shared/api';
import { useAnalytics } from '../../posthog/useAnalytics';
import { PROFILE_COPY } from './copy';

type ReloadReason = 'saved' | 'unknown' | 'unavailable';
type ProfileUpdateRequest =
  | { readonly status: 'idle' | 'saving' | 'refreshing' }
  | { readonly status: 'error'; readonly error: Error }
  | { readonly status: 'reload-required'; readonly reason: ReloadReason };

interface SavedProfile {
  readonly profile: ProfileResponse;
  readonly saved: boolean;
}

interface UseProfileUpdateResult {
  readonly request: ProfileUpdateRequest;
  readonly execute: (changes: ProfileChanges) => Promise<SavedProfile | null>;
  readonly reload: (reason: ReloadReason) => Promise<SavedProfile | null>;
  readonly reset: () => void;
}

const useProfileUpdate = (): UseProfileUpdateResult => {
  const { updateNickname } = useAnalytics();
  const [request, setRequest] = useState<ProfileUpdateRequest>({
    status: 'idle',
  });

  const reload = async (reason: ReloadReason): Promise<SavedProfile | null> => {
    setRequest({ status: 'refreshing' });
    try {
      const profile = await getUserProfile();
      updateNickname(profile.nickname);
      setRequest({ status: 'idle' });
      return { profile, saved: reason === 'saved' };
    } catch {
      setRequest({ status: 'reload-required', reason });
      return null;
    }
  };

  const execute = async (
    changes: ProfileChanges,
  ): Promise<SavedProfile | null> => {
    setRequest({ status: 'saving' });
    try {
      await updateUserProfile(changes);
    } catch (error: unknown) {
      if (error instanceof ProfileSaveResultUnknownError) {
        setRequest({ status: 'reload-required', reason: 'unknown' });
      } else if (
        error instanceof RequestError &&
        error.problem.code === 'PUBLIC_PROFILE_NOT_ASSIGNED'
      ) {
        setRequest({ status: 'reload-required', reason: 'unavailable' });
      } else {
        setRequest({
          status: 'error',
          error:
            error instanceof Error ? error : new Error(PROFILE_COPY.saveError),
        });
      }
      return null;
    }
    return reload('saved');
  };

  const reset = (): void => setRequest({ status: 'idle' });
  return { request, execute, reload, reset };
};

export default useProfileUpdate;
