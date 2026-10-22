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

interface ProfileUpdateError {
  readonly status: 'error';
  readonly error: Error;
}

interface ProfileReloadRequired {
  readonly status: 'reload-required';
  readonly reason: 'saved' | 'unknown' | 'unavailable';
}

interface ProfileUpdateSuccess {
  readonly status: 'success';
  readonly profile: ProfileResponse;
  readonly saved: boolean;
}

type ProfileUpdateResult =
  ProfileUpdateError | ProfileReloadRequired | ProfileUpdateSuccess;

type ProfileUpdateRequest =
  | { readonly status: 'idle' | 'saving' | 'refreshing' }
  | ProfileUpdateError
  | ProfileReloadRequired;

interface UseProfileUpdateResult {
  readonly request: ProfileUpdateRequest;
  readonly execute: (changes: ProfileChanges) => Promise<ProfileUpdateResult>;
  readonly reload: (
    reason: ProfileReloadRequired['reason'],
  ) => Promise<ProfileUpdateResult>;
  readonly reset: () => void;
}

const useProfileUpdate = (): UseProfileUpdateResult => {
  const { updateNickname } = useAnalytics();
  const [request, setRequest] = useState<ProfileUpdateRequest>({
    status: 'idle',
  });

  const reload = async (
    reason: ProfileReloadRequired['reason'],
  ): Promise<ProfileUpdateResult> => {
    setRequest({ status: 'refreshing' });
    try {
      const profile = await getUserProfile();
      updateNickname(profile.nickname);
      setRequest({ status: 'idle' });
      return { status: 'success', profile, saved: reason === 'saved' };
    } catch {
      const result: ProfileReloadRequired = {
        status: 'reload-required',
        reason,
      };
      setRequest(result);
      return result;
    }
  };

  const execute = async (
    changes: ProfileChanges,
  ): Promise<ProfileUpdateResult> => {
    setRequest({ status: 'saving' });
    try {
      await updateUserProfile(changes);
    } catch (error: unknown) {
      let result: ProfileUpdateError | ProfileReloadRequired;
      if (error instanceof ProfileSaveResultUnknownError) {
        result = { status: 'reload-required', reason: 'unknown' };
      } else if (
        error instanceof RequestError &&
        error.problem.code === 'PUBLIC_PROFILE_NOT_ASSIGNED'
      ) {
        result = { status: 'reload-required', reason: 'unavailable' };
      } else {
        result = {
          status: 'error',
          error:
            error instanceof Error ? error : new Error(PROFILE_COPY.saveError),
        };
      }
      setRequest(result);
      return result;
    }
    return reload('saved');
  };

  const reset = (): void => setRequest({ status: 'idle' });

  return { request, execute, reload, reset };
};

export default useProfileUpdate;
