import { useEffect, useRef, useState, type JSX, type RefObject } from 'react';
import { css, type SerializedStyles } from '@emotion/react';
import type { LandingExample } from './copy';
import LandingComicPreview from './LandingComicPreview';
import { theme } from '../../styles/theme';

interface LandingExampleDemoProps {
  readonly example: LandingExample;
  readonly pageRef: RefObject<HTMLElement | null>;
}

// 탭을 바꾸면 다시 시작하고, 입력 → 네컷 등장 연출의 타이머를 관리한다.
const LandingExampleDemo = ({
  example,
  pageRef,
}: LandingExampleDemoProps): JSX.Element => {
  const figureRef = useRef<HTMLElement>(null);
  const [typedLength, setTypedLength] = useState((): number =>
    typeof IntersectionObserver === 'undefined' ||
    window.matchMedia?.('(prefers-reduced-motion: reduce)').matches
      ? example.content.length
      : 0,
  );
  const complete = typedLength >= example.content.length;

  useEffect(() => {
    const element = figureRef.current;
    const media = window.matchMedia?.('(prefers-reduced-motion: reduce)');
    if (
      !element ||
      typeof IntersectionObserver === 'undefined' ||
      media?.matches
    )
      return;
    let timer: number | undefined;
    const stop = (): void => {
      window.clearInterval(timer);
      timer = undefined;
    };
    const observer = new IntersectionObserver(
      (entries): void => {
        if (!entries.some((entry): boolean => entry.isIntersecting)) return;
        observer.disconnect();
        const startedAt = performance.now();
        timer = window.setInterval((): void => {
          const nextLength = Math.min(
            Math.floor((performance.now() - startedAt) / 24) * 4,
            example.content.length,
          );
          setTypedLength(nextLength);
          if (nextLength >= example.content.length) stop();
        }, 24);
      },
      {
        root: pageRef.current,
        threshold: 0.35,
        rootMargin: '0px 0px -80px 0px',
      },
    );
    observer.observe(element);
    const handleMotionChange = (): void => {
      if (media?.matches) {
        stop();
        observer.disconnect();
        setTypedLength(example.content.length);
      }
    };
    media?.addEventListener('change', handleMotionChange);
    return (): void => {
      stop();
      observer.disconnect();
      media?.removeEventListener('change', handleMotionChange);
    };
  }, [example.content, pageRef]);

  return (
    <figure
      ref={figureRef}
      id="landing-example"
      css={cardStyle}
      data-demo-stage={complete ? 'complete' : 'typing'}
    >
      <figcaption css={sourceStyle}>
        <p css={fullTextStyle(complete)}>{example.content}</p>
        {!complete && (
          <p aria-hidden="true" css={typedTextStyle}>
            {example.content.slice(0, typedLength)}
            <span css={cursorStyle}>▏</span>
          </p>
        )}
      </figcaption>
      <p css={arrowStyle} aria-hidden="true">
        ↓
      </p>
      <LandingComicPreview example={example} />
    </figure>
  );
};

export default LandingExampleDemo;

const cardStyle = css`
  margin: 0;
  padding: 16px;
  border: 1px solid ${theme.colors.stroke.brandWeak};
  border-radius: 20px;
  background-color: ${theme.colors.background.surface};
  &[data-demo-stage='typing'] [data-comic-mask] {
    opacity: 1;
  }
  &[data-demo-stage='typing'] > p {
    opacity: 0;
  }
  &[data-demo-stage='complete'] > p {
    transition: opacity 0.2s ease-out;
  }
  @media (prefers-reduced-motion: reduce) {
    &[data-demo-stage] [data-comic-mask] {
      opacity: 0;
      animation: none;
    }
    &[data-demo-stage] > p {
      opacity: 1;
      transition: none;
    }
  }
`;
const sourceStyle = css`
  position: relative;
`;
const fullTextStyle = (complete: boolean): SerializedStyles => css`
  margin: 0;
  opacity: ${complete ? 1 : 0};
  font-size: 14px;
  line-height: 25px;
  word-break: keep-all;
`;
const typedTextStyle = css`
  position: absolute;
  inset: 0;
  margin: 0;
  font-size: 14px;
  line-height: 25px;
  word-break: keep-all;
`;
const cursorStyle = css`
  color: ${theme.colors.foreground.brand};
`;
const arrowStyle = css`
  margin: 14px 0;
  color: ${theme.colors.foreground.brand};
  font-size: 24px;
  text-align: center;
`;
