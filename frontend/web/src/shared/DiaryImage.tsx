import { css } from '@emotion/react';
import { useState } from 'react';
import { theme } from '../styles/theme';

const DiaryImage = ({
  src,
  alt,
  className,
}: {
  src: string;
  alt: string;
  className?: string;
}) => {
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
      onError={() => setFailedSrc(src)}
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
