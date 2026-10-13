import { css } from '@emotion/react';
import type { CSSProperties, JSX } from 'react';
import type { LandingExample } from './copy';

// 원본 한 장 위의 가림막만 사라지게 해 분할 이미지의 이음새를 없앤다.
const LandingComicPreview = ({
  example,
}: {
  example: Pick<LandingExample, 'title' | 'imageUrl'>;
}): JSX.Element => {
  return (
    <div
      role="img"
      aria-label={`${example.title} 네컷만화`}
      css={imageFrameStyle}
    >
      <img src={example.imageUrl} alt="" aria-hidden="true" css={imageStyle} />
      <div css={imagePanelsStyle} aria-hidden="true">
        {[0, 1, 2, 3].map((index): JSX.Element => (
          <div
            key={index}
            data-comic-mask
            css={imageMaskStyle}
            style={
              {
                '--panel-index': index,
                clipPath: `inset(${Math.floor(index / 2) * 50}% ${index % 2 === 0 ? 50 : 0}% ${index < 2 ? 50 : 0}% ${(index % 2) * 50}%)`,
              } as CSSProperties
            }
          />
        ))}
      </div>
    </div>
  );
};

export default LandingComicPreview;

const imageStyle = css`
  display: block;
  width: 100%;
  height: auto;
  aspect-ratio: 1;
`;

const imageFrameStyle = css`
  position: relative;
  overflow: hidden;
  border-radius: 12px;
`;
const imagePanelsStyle = css`
  position: absolute;
  inset: 0;
  pointer-events: none;
`;
const imageMaskStyle = css`
  position: absolute;
  inset: 0;
  background-color: #fff;
  opacity: 0;
`;
