import { RequestError } from '../../shared/api';
import { PROFILE_COPY } from './copy';

export type ProfileErrorField = 'nickname' | 'photo' | 'form';
export interface ProfileErrors {
  readonly nickname: string;
  readonly photo: string;
  readonly form: string;
}
export const EMPTY_PROFILE_ERRORS: ProfileErrors = {
  nickname: '',
  photo: '',
  form: '',
};

export const getProfileErrors = (error: unknown): ProfileErrors => {
  if (!(error instanceof RequestError)) {
    return { ...EMPTY_PROFILE_ERRORS, form: PROFILE_COPY.saveError };
  }
  const { problem } = error;
  const nickname =
    problem.errors?.find(({ field }) => field === 'nickname')?.reason ??
    (problem.code === 'NICKNAME_ALREADY_IN_USE'
      ? PROFILE_COPY.nicknameDuplicate
      : '');
  const photo =
    problem.errors?.find(
      ({ field }) => field === 'image' || field === 'imageCode',
    )?.reason ??
    (problem.status === 413
      ? PROFILE_COPY.fileSizeError
      : problem.status === 415
        ? PROFILE_COPY.fileTypeError
        : '');
  // 필드 정보가 없는 400 오류는 문구를 추측하지 않고 폼 전체에 표시한다.
  return {
    nickname,
    photo,
    form:
      nickname || photo
        ? ''
        : problem.status === 400
          ? error.message
          : PROFILE_COPY.saveError,
  };
};
