import { useEffect, useRef, useState, type JSX, type RefObject } from 'react';
import { css, type SerializedStyles } from '@emotion/react';
import type { LandingExample } from './copy';
import LandingComicPreview from './LandingComicPreview';

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
      aria-label={`${example.title} 예시`}
      aria-live="polite"
    >
      <figcaption css={sourceStyle}>
        <div css={textAreaStyle}>
          <p css={fullTextStyle(complete)}>{example.content}</p>
          {!complete && (
            <p aria-hidden="true" css={typedTextStyle}>
              {example.content.slice(0, typedLength)}
              <span css={cursorStyle}>▏</span>
            </p>
          )}
        </div>
      </figcaption>
      <div css={comicStyle}>
        <LandingComicPreview example={example} />
      </div>
    </figure>
  );
};

export default LandingExampleDemo;

const cardStyle = css`
  position: relative;
  width: min(100%, 570px);
  margin: 0 auto;
  padding: 24px;
  border: 1px solid #e9e2f8;
  border-radius: 30px;
  background: #fff;
  box-shadow:
    0 28px 75px rgb(83 55 148 / 12%),
    0 5px 18px rgb(83 55 148 / 5%);
  &[data-demo-stage='typing'] [data-comic-mask] {
    opacity: 1;
  }
  @media (max-width: 767px) {
    padding: 16px;
    border-radius: 24px;
  }
  @media (prefers-reduced-motion: reduce) {
    &[data-demo-stage] [data-comic-mask] {
      opacity: 0;
      animation: none;
    }
  }
`;
const sourceStyle = css`
  padding: 19px 20px;
  border: 1px solid #f0eafb;
  border-radius: 18px;
  background: #faf8ff;
  @media (max-width: 767px) {
    padding: 16px;
    border-radius: 16px;
  }
`;
const textAreaStyle = css`
  position: relative;
  min-height: 50px;
`;
const fullTextStyle = (complete: boolean): SerializedStyles => css`
  margin: 0;
  opacity: ${complete ? 1 : 0};
  color: #30283a;
  font-size: 14px;
  font-weight: 500;
  line-height: 24px;
  word-break: keep-all;
`;
const typedTextStyle = css`
  position: absolute;
  inset: 0;
  margin: 0;
  color: #30283a;
  font-size: 14px;
  font-weight: 500;
  line-height: 24px;
  word-break: keep-all;
`;
const cursorStyle = css`
  color: #7355da;
`;
const comicStyle = css`
  margin-top: 16px;
  padding: 15px;
  border: 1px solid #eee9f6;
  border-radius: 18px;
  background: #fff;
  @media (max-width: 767px) {
    padding: 10px;
    border-radius: 15px;
  }
`;
