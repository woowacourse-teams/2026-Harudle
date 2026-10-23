import { css } from '@emotion/react';
import {
  useImperativeHandle,
  useRef,
  useState,
  type FormEvent,
  type Ref,
} from 'react';
import Modal, { type ModalHandle } from '../../shared/Modal';
import { useToastContext } from '../../shared/ToastContext';
import type {
  ProfileChanges,
  ProfileResponse,
} from '../../domain/user/profile';
import { theme } from '../../styles/theme';
import useProfileUpdate, { type SavedProfile } from './useProfileUpdate';
import ProfilePhotoField, { type SelectedPhoto } from './ProfilePhotoField';
import { PROFILE_IMAGES } from './profileImages';
import { PROFILE_COPY } from './copy';

interface ProfileEditModalProps {
  readonly profile: ProfileResponse;
  readonly onSaved: (profile: ProfileResponse) => void;
  readonly ref: Ref<ModalHandle>;
}

const ProfileEditModal = ({ profile, onSaved, ref }: ProfileEditModalProps) => {
  const modalRef = useRef<ModalHandle>(null);
  const composing = useRef(false);
  const [nickname, setNickname] = useState('');
  const [photo, setPhoto] = useState<SelectedPhoto>({ type: 'current' });
  const [canSubmitPhoto, setCanSubmitPhoto] = useState(true);
  const [session, setSession] = useState(0);
  const {
    request,
    errors,
    saveProfile,
    retryProfileFetch,
    isPending,
    clearFieldError,
    setFieldError,
    reset,
  } = useProfileUpdate();
  const { addToast } = useToastContext();
  const isBusy = request.status === 'saving' || request.status === 'refreshing';
  const needsReload = request.status === 'reload-required';
  const locked = isBusy || needsReload;
  const currentCode = PROFILE_IMAGES.find(({ url }): boolean =>
    profile.profileImageUrl.split('?')[0].endsWith(url),
  )?.code;
  const photoChanged =
    photo.type === 'upload' ||
    (photo.type === 'default' && photo.code !== currentCode);
  const changed = nickname !== profile.nickname || photoChanged;
  const formMessage = needsReload
    ? {
        saved: PROFILE_COPY.refreshError,
        unknown: PROFILE_COPY.unknownResult,
        unavailable: PROFILE_COPY.unavailable,
      }[request.reason]
    : errors.form;

  useImperativeHandle(ref, (): ModalHandle => ({
    open: (): void => {
      if (isPending()) return;
      if (!needsReload) {
        setNickname(profile.nickname);
        setPhoto({ type: 'current' });
        setCanSubmitPhoto(true);
        setSession((previous): number => previous + 1);
        composing.current = false;
        reset();
      }
      modalRef.current?.open();
    },
    close: (): void => {
      if (!isPending()) modalRef.current?.close();
    },
  }));

  const applyResult = (result: SavedProfile | null): void => {
    if (!result) return;
    onSaved(result.profile);
    modalRef.current?.close();
    if (result.saved)
      addToast({ type: 'success', message: PROFILE_COPY.success }, 2000);
  };
  const handleSubmit = async (
    event: FormEvent<HTMLFormElement>,
  ): Promise<void> => {
    event.preventDefault();
    if (locked || composing.current || !changed || !canSubmitPhoto) return;
    if (
      nickname !== profile.nickname &&
      (!nickname.trim() || Array.from(nickname).length > 10)
    ) {
      setFieldError('nickname', PROFILE_COPY.nicknameLengthError);
      return;
    }
    const changes: ProfileChanges = {
      ...(nickname !== profile.nickname && { nickname }),
      ...(photoChanged && { image: photo }),
    };
    applyResult(await saveProfile(changes));
  };
  const handleReload = async (): Promise<void> => {
    applyResult(await retryProfileFetch());
  };
  return (
    <Modal
      ref={modalRef}
      title={PROFILE_COPY.title}
      dismissible={!isBusy}
      footer={
        <>
          <button
            type="button"
            css={cancelNicknameButtonStyle}
            disabled={isBusy}
            onClick={(): void => modalRef.current?.close()}
          >
            {needsReload ? '닫기' : '취소'}
          </button>
          <button
            type={needsReload ? 'button' : 'submit'}
            form="profile-form"
            css={saveNicknameButtonStyle}
            disabled={isBusy || (!needsReload && (!changed || !canSubmitPhoto))}
            onClick={
              needsReload
                ? (): void => {
                    void handleReload();
                  }
                : undefined
            }
          >
            {request.status === 'saving'
              ? '저장 중...'
              : request.status === 'refreshing'
                ? '불러오는 중...'
                : needsReload
                  ? PROFILE_COPY.reload
                  : '저장'}
          </button>
        </>
      }
    >
      <form
        id="profile-form"
        onSubmit={(event): void => {
          void handleSubmit(event);
        }}
      >
        <ProfilePhotoField
          key={session}
          currentImageUrl={profile.profileImageUrl}
          disabled={locked}
          error={errors.photo}
          onChange={(selection, canSubmit): void => {
            setPhoto(selection);
            setCanSubmitPhoto(canSubmit);
            clearFieldError('photo');
          }}
        />
        <label css={nicknameInputLabelStyle} htmlFor="nickname">
          닉네임
        </label>
        <input
          id="nickname"
          css={[nicknameInputStyle, errors.nickname && nicknameInputErrorStyle]}
          value={nickname}
          disabled={locked}
          aria-invalid={Boolean(errors.nickname)}
          aria-describedby="nickname-help nickname-error"
          onCompositionStart={() => {
            composing.current = true;
          }}
          onCompositionEnd={(event) => {
            composing.current = false;
            setNickname(
              Array.from(event.currentTarget.value).slice(0, 10).join(''),
            );
          }}
          onKeyDown={(event) => {
            if (
              event.key === 'Enter' &&
              (composing.current || event.nativeEvent.isComposing)
            )
              event.preventDefault();
          }}
          onChange={(event) => {
            setNickname(
              composing.current
                ? event.target.value
                : Array.from(event.target.value).slice(0, 10).join(''),
            );
            clearFieldError('nickname');
          }}
        />
        <p id="nickname-help" css={nicknameHelpStyle}>
          {Array.from(nickname).length} / 10
        </p>
        {errors.nickname && (
          <p id="nickname-error" css={nicknameErrorStyle} role="alert">
            {errors.nickname}
          </p>
        )}
        {formMessage && (
          <p css={nicknameErrorStyle} role="alert">
            {formMessage}
          </p>
        )}
      </form>
    </Modal>
  );
};

export default ProfileEditModal;

const nicknameInputLabelStyle = css`
  display: block;
  margin: 12px 0 8px;
  color: ${theme.colors.foreground.neutral};
  font-weight: 700;
`;

const nicknameInputStyle = css`
  width: 100%;
  height: 44px;
  padding: 0 12px;
  border: 1px solid ${theme.colors.stroke.outline};
  border-radius: 8px;
  background-color: ${theme.colors.background.surface};
  color: ${theme.colors.foreground.neutral};
  font-size: 16px;
`;

const nicknameInputErrorStyle = css`
  border-color: ${theme.colors.stroke.critical};
`;

const nicknameHelpStyle = css`
  margin-top: 10px;
  font-size: 12px;
  line-height: 20px;
  word-break: keep-all;
  overflow-wrap: anywhere;
`;

const nicknameErrorStyle = css`
  ${nicknameHelpStyle};
  color: ${theme.colors.foreground.critical};
`;

const nicknameButtonStyle = css`
  flex: 1;
  min-width: 0;
  height: 44px;
  border: 0;
  border-radius: 8px;
  font-size: 14px;
  font-weight: 500;
  cursor: pointer;

  &:disabled {
    opacity: 0.5;
    cursor: default;
  }
`;

const cancelNicknameButtonStyle = css`
  ${nicknameButtonStyle};
  background-color: ${theme.colors.background.neutralSubtle};
  color: ${theme.colors.foreground.neutral};
`;

const saveNicknameButtonStyle = css`
  ${nicknameButtonStyle};
  background-color: ${theme.colors.background.brandSolid};
  color: ${theme.colors.foreground.onBrand};
`;
