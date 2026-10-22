import { css } from '@emotion/react';
import {
  useEffect,
  useImperativeHandle,
  useRef,
  useState,
  type ChangeEvent,
  type FormEvent,
  type Ref,
} from 'react';
import Modal, { type ModalHandle } from '../../shared/Modal';
import { RequestError } from '../../shared/api';
import { useToastContext } from '../../shared/ToastContext';
import type {
  ProfileChanges,
  ProfileResponse,
} from '../../domain/user/profile';
import profileFallbackImage from '../../assets/images/profile-fallback.svg';
import { theme } from '../../styles/theme';
import useProfileUpdate from './useProfileUpdate';
import { PROFILE_COPY } from './copy';
import { PROFILE_IMAGES } from './profileImages';

interface ProfileEditModalProps {
  profile: ProfileResponse;
  onSaved: (profile: ProfileResponse) => void;
  onBusyChange: (busy: boolean) => void;
  ref: Ref<ModalHandle>;
}

interface ProfileError {
  readonly field: 'nickname' | 'photo' | 'form';
  readonly message: string;
}

type SelectedPhoto =
  | { readonly type: 'current' }
  | { readonly type: 'default'; readonly code: number }
  | { readonly type: 'upload'; readonly file: File; readonly preview: string };

const getProfileError = (error: Error): ProfileError => {
  if (error instanceof RequestError) {
    const problem = error.problem;
    const nicknameError = problem.errors?.find(
      ({ field }) => field === 'nickname',
    );
    if (problem.code === 'NICKNAME_ALREADY_IN_USE' || nicknameError) {
      return {
        field: 'nickname',
        message: nicknameError?.reason ?? PROFILE_COPY.nicknameDuplicate,
      };
    }
    if (problem.status === 413 || problem.status === 415) {
      return {
        field: 'photo',
        message:
          problem.status === 413
            ? PROFILE_COPY.fileSizeError
            : PROFILE_COPY.fileTypeError,
      };
    }
    if (problem.status === 400 && problem.detail.includes('이미지')) {
      return { field: 'photo', message: PROFILE_COPY.fileProcessError };
    }
    if (problem.status === 400)
      return { field: 'form', message: error.message };
  }
  return { field: 'form', message: PROFILE_COPY.saveError };
};

const ProfileEditModal = ({
  profile,
  onSaved,
  onBusyChange,
  ref,
}: ProfileEditModalProps) => {
  const modalRef = useRef<ModalHandle>(null);
  const formRef = useRef<HTMLFormElement>(null);
  const fileInputRef = useRef<HTMLInputElement>(null);
  const readerRef = useRef<FileReader | null>(null);
  const pending = useRef(false);
  const composing = useRef(false);
  const [nickname, setNickname] = useState('');
  const [nicknameError, setNicknameError] = useState('');
  const [photo, setPhoto] = useState<SelectedPhoto>({ type: 'current' });
  const [photoError, setPhotoError] = useState('');
  const [isReading, setIsReading] = useState(false);
  const [brokenImages, setBrokenImages] = useState<number[]>([]);
  const { request, execute, reload, reset } = useProfileUpdate();
  const { addToast } = useToastContext();
  const isBusy = request.status === 'saving' || request.status === 'refreshing';
  const needsReload = request.status === 'reload-required';
  const locked = isBusy || needsReload;
  const serverError =
    request.status === 'error' ? getProfileError(request.error) : null;
  const nicknameMessage =
    nicknameError ||
    (serverError?.field === 'nickname' ? serverError.message : '');
  const photoMessage =
    photoError || (serverError?.field === 'photo' ? serverError.message : '');
  const formMessage = needsReload
    ? {
        saved: PROFILE_COPY.refreshError,
        unknown: PROFILE_COPY.unknownResult,
        unavailable: PROFILE_COPY.unavailable,
      }[request.reason]
    : serverError?.field === 'form'
      ? serverError.message
      : '';
  const currentCode = PROFILE_IMAGES.find(({ url }) =>
    profile.profileImageUrl.split('?')[0].endsWith(url),
  )?.code;
  const selectedCode =
    photo.type === 'default'
      ? photo.code
      : photo.type === 'current'
        ? currentCode
        : undefined;
  const photoChanged =
    photo.type === 'upload' ||
    (photo.type === 'default' && photo.code !== currentCode);
  const changed = nickname !== profile.nickname || photoChanged;
  const preview =
    photo.type === 'upload'
      ? photo.preview
      : photo.type === 'default'
        ? PROFILE_IMAGES[photo.code - 1]?.url
        : profile.profileImageUrl;

  useEffect(() => () => readerRef.current?.abort(), []);

  const cancelRead = (): void => {
    readerRef.current?.abort();
    readerRef.current = null;
    setIsReading(false);
  };

  useImperativeHandle(ref, () => ({
    open: (): void => {
      if (!pending.current && !needsReload) {
        cancelRead();
        setNickname(profile.nickname);
        setNicknameError('');
        setPhoto({ type: 'current' });
        setPhotoError('');
        reset();
      }
      modalRef.current?.open();
    },
    close: (): void => {
      if (!pending.current) modalRef.current?.close();
    },
  }));

  const runUpdate = async (changes?: ProfileChanges): Promise<void> => {
    if (pending.current) return;
    pending.current = true;
    onBusyChange(true);
    try {
      const result = changes
        ? await execute(changes)
        : needsReload
          ? await reload(request.reason)
          : null;
      if (result?.status === 'success') {
        onSaved(result.profile);
        modalRef.current?.close();
        if (result.saved)
          addToast({ type: 'success', message: PROFILE_COPY.success }, 2000);
      } else if (
        result?.status === 'error' &&
        !formRef.current?.closest('dialog')?.open
      ) {
        addToast(
          {
            type: 'error',
            message: PROFILE_COPY.closedSaveError,
            description: PROFILE_COPY.editAgain,
          },
          2000,
        );
      }
    } finally {
      pending.current = false;
      onBusyChange(false);
    }
  };

  const handleSubmit = (event: FormEvent<HTMLFormElement>): void => {
    event.preventDefault();
    if (locked || isReading || composing.current || !changed || photoMessage)
      return;
    if (
      nickname !== profile.nickname &&
      (!nickname.trim() || Array.from(nickname).length > 10)
    ) {
      setNicknameError(PROFILE_COPY.nicknameLengthError);
      return;
    }
    setNicknameError('');
    const changes: ProfileChanges = {
      ...(nickname !== profile.nickname && { nickname }),
      ...(photoChanged && {
        image:
          photo.type === 'default'
            ? { type: 'default', code: photo.code }
            : { type: 'upload', file: photo.file },
      }),
    };
    void runUpdate(changes);
  };

  const handleFileChange = (event: ChangeEvent<HTMLInputElement>): void => {
    const file = event.currentTarget.files?.[0];
    event.currentTarget.value = '';
    if (!file) return;
    cancelRead();
    if (serverError?.field === 'photo') reset();
    if (!['image/jpeg', 'image/png'].includes(file.type)) {
      setPhotoError(PROFILE_COPY.fileTypeError);
      return;
    }
    if (file.size > 5 * 1024 * 1024 || file.size === 0) {
      setPhotoError(
        file.size === 0
          ? PROFILE_COPY.fileProcessError
          : PROFILE_COPY.fileSizeError,
      );
      return;
    }
    const reader = new FileReader();
    readerRef.current = reader;
    setIsReading(true);
    reader.onload = (): void => {
      if (readerRef.current !== reader) return;
      if (typeof reader.result === 'string') {
        setPhoto({ type: 'upload', file, preview: reader.result });
        setPhotoError('');
      } else setPhotoError(PROFILE_COPY.fileReadError);
      readerRef.current = null;
      setIsReading(false);
    };
    reader.onerror = (): void => {
      if (readerRef.current !== reader) return;
      setPhotoError(PROFILE_COPY.fileReadError);
      readerRef.current = null;
      setIsReading(false);
    };
    reader.readAsDataURL(file);
  };

  return (
    <Modal
      ref={modalRef}
      title={PROFILE_COPY.title}
      footer={
        <>
          <button
            type="button"
            css={cancelNicknameButtonStyle}
            disabled={isBusy}
            onClick={() => modalRef.current?.close()}
          >
            {needsReload ? '닫기' : '취소'}
          </button>
          <button
            type={needsReload ? 'button' : 'submit'}
            form="profile-form"
            css={saveNicknameButtonStyle}
            disabled={
              isBusy ||
              isReading ||
              (!needsReload && (!changed || Boolean(photoMessage)))
            }
            onClick={needsReload ? () => void runUpdate() : undefined}
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
      <form id="profile-form" ref={formRef} onSubmit={handleSubmit}>
        <div css={photoSectionStyle}>
          <img
            css={previewStyle}
            src={preview || profileFallbackImage}
            alt="선택한 프로필 사진"
            onError={(event) => {
              if (
                event.currentTarget.getAttribute('src') === profileFallbackImage
              )
                return;
              event.currentTarget.src = profileFallbackImage;
              if (photo.type !== 'current')
                setPhotoError(PROFILE_COPY.fileReadError);
            }}
          />
          <div css={candidatesStyle} aria-label="기본 프로필 사진">
            {PROFILE_IMAGES.map(({ code, name, url }) => (
              <button
                key={code}
                type="button"
                css={[
                  candidateStyle,
                  selectedCode === code && selectedPhotoStyle,
                ]}
                aria-label={`${name} 프로필 사진`}
                aria-pressed={selectedCode === code}
                disabled={locked || brokenImages.includes(code)}
                onClick={() => {
                  cancelRead();
                  setPhoto({ type: 'default', code });
                  setPhotoError('');
                  if (serverError?.field === 'photo') reset();
                }}
              >
                <img
                  src={brokenImages.includes(code) ? profileFallbackImage : url}
                  alt=""
                  onError={() => {
                    setBrokenImages((previous) =>
                      previous.includes(code) ? previous : [...previous, code],
                    );
                    if (photo.type === 'default' && photo.code === code)
                      setPhotoError(PROFILE_COPY.candidateError);
                  }}
                />
              </button>
            ))}
          </div>
          <input
            ref={fileInputRef}
            type="file"
            accept="image/jpeg,image/png"
            hidden
            aria-label="프로필 사진 파일"
            disabled={locked}
            onChange={handleFileChange}
          />
          <button
            type="button"
            css={uploadButtonStyle}
            disabled={locked}
            onClick={() => fileInputRef.current?.click()}
          >
            {PROFILE_COPY.upload}
          </button>
          <p
            css={[nicknameHelpStyle, photoMessage && nicknameErrorStyle]}
            role={photoMessage ? 'alert' : undefined}
          >
            {photoMessage || PROFILE_COPY.fileHelp}
          </p>
        </div>
        <label css={nicknameInputLabelStyle} htmlFor="nickname">
          닉네임
        </label>
        <input
          id="nickname"
          css={[nicknameInputStyle, nicknameMessage && nicknameInputErrorStyle]}
          value={nickname}
          disabled={locked}
          aria-invalid={Boolean(nicknameMessage)}
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
            setNicknameError('');
            if (serverError?.field === 'nickname') reset();
          }}
        />
        <p id="nickname-help" css={nicknameHelpStyle}>
          {Array.from(nickname).length} / 10
        </p>
        {nicknameMessage && (
          <p id="nickname-error" css={nicknameErrorStyle} role="alert">
            {nicknameMessage}
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

const photoSectionStyle = css`
  display: flex;
  flex-direction: column;
  gap: 8px;
`;

const previewStyle = css`
  width: 112px;
  height: 112px;
  align-self: center;
  border-radius: 50%;
  object-fit: cover;
  margin: 8px 0;
`;

const candidatesStyle = css`
  display: grid;
  grid-template-columns: repeat(5, minmax(0, 1fr));
  gap: 8px;
`;

const candidateStyle = css`
  min-width: 0;
  aspect-ratio: 1;
  padding: 2px;
  border: 1px solid ${theme.colors.stroke.outline};
  border-radius: 12px;
  background-color: ${theme.colors.background.surface};
  cursor: pointer;

  img {
    display: block;
    width: 100%;
    height: 100%;
    object-fit: contain;
    border-radius: 8px;
  }

  &:disabled {
    opacity: 0.5;
    cursor: default;
  }
`;

const selectedPhotoStyle = css`
  border-color: ${theme.colors.stroke.brandSolid};
  box-shadow: inset 0 0 0 1px ${theme.colors.stroke.brandSolid};
  background-color: ${theme.colors.background.brandWeak};
`;

const uploadButtonStyle = css`
  ${nicknameButtonStyle};
  flex: none;
  min-height: 44px;
  width: 100%;
  border: 1px solid ${theme.colors.stroke.outline};
  background-color: ${theme.colors.background.surface};
  color: ${theme.colors.foreground.neutral};
`;
