import { css } from '@emotion/react';
import { useEffect, useRef, useState, type ChangeEvent } from 'react';
import profileFallbackImage from '../../assets/images/profile-fallback.svg';
import { theme } from '../../styles/theme';
import { PROFILE_COPY } from './copy';
import { PROFILE_IMAGES } from './profileImages';

export type SelectedPhoto =
  | { readonly type: 'current' }
  | { readonly type: 'default'; readonly code: number }
  | { readonly type: 'upload'; readonly file: File };
type PhotoPreview =
  | { readonly type: 'current' }
  | { readonly type: 'default'; readonly code: number }
  | { readonly type: 'upload'; readonly file: File; readonly preview: string };
interface ProfilePhotoFieldProps {
  readonly currentImageUrl: string;
  readonly disabled: boolean;
  readonly error: string;
  readonly onChange: (photo: SelectedPhoto, canSubmit: boolean) => void;
}
const MAX_PHOTO_BYTES = 5_000_000;

const ProfilePhotoField = ({
  currentImageUrl,
  disabled,
  error,
  onChange,
}: ProfilePhotoFieldProps) => {
  const fileInputRef = useRef<HTMLInputElement>(null);
  const [photo, setPhoto] = useState<PhotoPreview>({ type: 'current' });
  const [photoError, setPhotoError] = useState('');
  const [brokenImages, setBrokenImages] = useState<number[]>([]);
  const [imageAttempt, setImageAttempt] = useState(0);
  const photoMessage = photoError || error;
  const currentCode = PROFILE_IMAGES.find(({ url }): boolean =>
    currentImageUrl.split('?')[0].endsWith(url),
  )?.code;
  const selectedCode =
    photo.type === 'default'
      ? photo.code
      : photo.type === 'current'
        ? currentCode
        : undefined;
  const preview =
    photo.type === 'upload'
      ? photo.preview
      : photo.type === 'default'
        ? PROFILE_IMAGES[photo.code - 1]?.url
        : currentImageUrl;

  useEffect(
    () => (): void => {
      if (photo.type === 'upload') URL.revokeObjectURL(photo.preview);
    },
    [photo],
  );

  const selectPhoto = (selection: PhotoPreview): void => {
    setPhoto(selection);
    setPhotoError('');
    // 미리보기 주소는 이 컴포넌트에서만 관리한다.
    onChange(
      selection.type === 'upload'
        ? { type: 'upload', file: selection.file }
        : selection,
      true,
    );
  };
  const handleFileChange = (event: ChangeEvent<HTMLInputElement>): void => {
    const file = event.currentTarget.files?.[0];
    event.currentTarget.value = '';
    if (!file) return;
    if (!['image/jpeg', 'image/png'].includes(file.type)) {
      setPhotoError(PROFILE_COPY.fileTypeError);
      return;
    }
    if (file.size > MAX_PHOTO_BYTES || file.size === 0) {
      setPhotoError(
        file.size === 0
          ? PROFILE_COPY.fileProcessError
          : PROFILE_COPY.fileSizeError,
      );
      return;
    }
    try {
      selectPhoto({ type: 'upload', file, preview: URL.createObjectURL(file) });
    } catch {
      setPhotoError(PROFILE_COPY.fileProcessError);
    }
  };
  return (
    <div css={photoSectionStyle}>
      <div css={previewSectionStyle}>
        <img
          css={previewStyle}
          key={preview}
          src={preview || profileFallbackImage}
          alt="선택한 프로필 사진"
          onError={(event) => {
            if (
              event.currentTarget.getAttribute('src') === profileFallbackImage
            )
              return;
            event.currentTarget.src = profileFallbackImage;
            if (photo.type === 'upload') {
              setPhotoError(PROFILE_COPY.fileReadError);
              onChange({ type: 'upload', file: photo.file }, false);
            }
          }}
        />
        {photo.type !== 'current' && (
          <button
            type="button"
            css={restoreButtonStyle}
            disabled={disabled}
            onClick={(): void => selectPhoto({ type: 'current' })}
          >
            {PROFILE_COPY.restorePhoto}
          </button>
        )}
      </div>
      <div css={candidatesStyle} aria-label="기본 프로필 사진">
        {PROFILE_IMAGES.map(({ code, name, url }) => (
          <button
            key={code}
            type="button"
            css={[candidateStyle, selectedCode === code && selectedPhotoStyle]}
            aria-label={`${name} 프로필 사진`}
            aria-pressed={selectedCode === code}
            disabled={disabled}
            onClick={() => {
              selectPhoto({ type: 'default', code });
            }}
          >
            <img
              key={imageAttempt}
              src={brokenImages.includes(code) ? profileFallbackImage : url}
              alt=""
              onError={() => {
                setBrokenImages((previous) =>
                  previous.includes(code) ? previous : [...previous, code],
                );
              }}
            />
          </button>
        ))}
      </div>
      {brokenImages.length > 0 && (
        <button
          type="button"
          disabled={disabled}
          onClick={(): void => {
            setBrokenImages([]);
            setImageAttempt((previous): number => previous + 1);
          }}
        >
          {PROFILE_COPY.retryImages}
        </button>
      )}
      <input
        ref={fileInputRef}
        type="file"
        accept="image/jpeg,image/png"
        hidden
        aria-label="프로필 사진 파일"
        disabled={disabled}
        onChange={handleFileChange}
      />
      <button
        type="button"
        css={uploadButtonStyle}
        disabled={disabled}
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
  );
};
export default ProfilePhotoField;

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

const photoSectionStyle = css`
  display: flex;
  flex-direction: column;
  gap: 8px;
`;

const previewSectionStyle = css`
  position: relative;
  display: flex;
  justify-content: center;
`;

const previewStyle = css`
  width: 112px;
  height: 112px;
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

const restoreButtonStyle = css`
  position: absolute;
  right: 0;
  bottom: 8px;
  height: 32px;
  padding: 0 10px;
  border: 0;
  border-radius: 8px;
  background-color: ${theme.colors.background.neutralSubtle};
  color: ${theme.colors.foreground.neutralMuted};
  font-size: 12px;
  font-weight: 500;
  cursor: pointer;

  &:hover:not(:disabled) {
    background-color: ${theme.colors.background.neutralWeak};
  }

  &:disabled {
    opacity: 0.5;
    cursor: default;
  }
`;

const uploadButtonStyle = css`
  min-width: 0;
  height: 44px;
  border-radius: 8px;
  font-size: 14px;
  font-weight: 500;
  cursor: pointer;
  &:disabled {
    opacity: 0.5;
    cursor: default;
  }
  flex: none;
  min-height: 44px;
  width: 100%;
  border: 1px solid ${theme.colors.stroke.outline};
  background-color: ${theme.colors.background.surface};
  color: ${theme.colors.foreground.neutral};
`;
