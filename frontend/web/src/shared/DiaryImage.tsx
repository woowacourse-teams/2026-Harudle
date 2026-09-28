import { css } from '@emotion/react';
import { useState, type ReactElement } from 'react';
import posthog from 'posthog-js';
import { isPostHogEnabled } from '../posthog/posthog';
import { theme } from '../styles/theme';

interface DiaryImageProps {
  readonly src: string;
  readonly alt: string;
  readonly className?: string;
  readonly diaryId?: string;
  readonly imageRole: 'thumbnail' | 'original';
}

const DiaryImage = ({
  src,
  alt,
  className,
  diaryId,
  imageRole,
}: DiaryImageProps): ReactElement => {
  const [failedSrc, setFailedSrc] = useState<string | null>(null);

  if (failedSrc === src) {
    return (
      <span
        className={className}
        css={fallbackStyle}
        role="img"
        aria-label={`${alt}: 현재 이미지를 불러올 수 없습니다`}
      >
        현재 이미지를
        <br />
        불러올 수 없습니다
      </span>
    );
  }

  return (
    <img
      className={className}
      src={src}
      alt={alt}
      onError={(): void => {
        setFailedSrc(src);

        if (isPostHogEnabled) {
          posthog.captureException(new Error('일기 이미지 로딩 실패'), {
            feature: 'diary_image',
            operation: 'load',
            image_role: imageRole,
            ...(diaryId !== undefined ? { diary_id: diaryId } : {}),
          });
        }
      }}
    />
  );
};

export default DiaryImage;

const fallbackStyle = css`
  display: grid;
  flex-shrink: 0;
  place-content: center;
  padding: 8px;
  background: ${theme.colors.background.neutralWeak};
  color: ${theme.colors.foreground.neutralMuted};
  font-size: 12px;
  line-height: 20px;
  text-align: center;
`;
