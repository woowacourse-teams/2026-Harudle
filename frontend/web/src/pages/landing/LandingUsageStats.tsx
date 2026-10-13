import { useEffect, useRef, useState, type JSX, type RefObject } from 'react';
import { css } from '@emotion/react';
import { LANDING_COPY } from './copy';
import { theme } from '../../styles/theme';

interface LandingUsageStatsProps {
  readonly pageRef: RefObject<HTMLElement | null>;
}

const LandingUsageStats = ({
  pageRef,
}: LandingUsageStatsProps): JSX.Element => {
  const statsRef = useRef<HTMLDListElement>(null);
  const [progress, setProgress] = useState((): number =>
    typeof IntersectionObserver === 'undefined' ||
    window.matchMedia?.('(prefers-reduced-motion: reduce)').matches
      ? 1
      : 0,
  );

  useEffect(() => {
    const element = statsRef.current;
    const media = window.matchMedia?.('(prefers-reduced-motion: reduce)');
    if (
      !element ||
      typeof IntersectionObserver === 'undefined' ||
      media?.matches
    )
      return;
    let frame: number | undefined;
    const stop = (): void => {
      if (frame !== undefined) window.cancelAnimationFrame(frame);
    };
    const observer = new IntersectionObserver(
      (entries): void => {
        if (!entries.some((entry): boolean => entry.isIntersecting)) return;
        observer.disconnect();
        const startedAt = performance.now();
        const animate = (now: number): void => {
          const elapsed = Math.min((now - startedAt) / 900, 1);
          setProgress(1 - Math.pow(1 - elapsed, 3));
          if (elapsed < 1) frame = window.requestAnimationFrame(animate);
        };
        frame = window.requestAnimationFrame(animate);
      },
      {
        root: pageRef.current,
        threshold: 0.6,
        rootMargin: '0px 0px -80px 0px',
      },
    );
    observer.observe(element);
    const handleMotionChange = (): void => {
      if (media?.matches) {
        stop();
        observer.disconnect();
        setProgress(1);
      }
    };
    media?.addEventListener('change', handleMotionChange);
    return (): void => {
      stop();
      observer.disconnect();
      media?.removeEventListener('change', handleMotionChange);
    };
  }, [pageRef]);

  return (
    <dl ref={statsRef} css={statsStyle} aria-label="하루들 이용 현황">
      {LANDING_COPY.share.usageStats.map((stat): JSX.Element => (
        <div key={stat.label} css={statStyle}>
          <dt css={labelStyle}>{stat.label}</dt>
          <dd css={numberStyle}>
            <span css={accessibleTextStyle}>
              {stat.prefix}
              {stat.value.toLocaleString('ko-KR')}
              {stat.suffix}
            </span>
            <span aria-hidden="true">
              <small>{stat.prefix}</small>
              {Math.round(stat.value * progress).toLocaleString('ko-KR')}
              <small>{stat.suffix}</small>
            </span>
          </dd>
        </div>
      ))}
    </dl>
  );
};

export default LandingUsageStats;

const statsStyle = css`
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 12px;
  margin: 8px 0 0;
  padding: 24px 12px;
  border-radius: 20px;
  background-color: ${theme.colors.background.brandWeak};
`;
const statStyle = css`
  display: flex;
  flex-direction: column;
  gap: 6px;
  text-align: center;
  &:last-child {
    border-left: 1px solid ${theme.colors.stroke.brandWeak};
  }
`;
const numberStyle = css`
  position: relative;
  order: -1;
  margin: 0;
  color: ${theme.colors.foreground.brand};
  font-size: clamp(28px, 8vw, 34px);
  font-weight: 800;
  line-height: 44px;
  letter-spacing: -0.02em;
  font-variant-numeric: tabular-nums;
  white-space: nowrap;
  small {
    font-size: 15px;
    font-weight: 600;
  }
`;
const labelStyle = css`
  color: ${theme.colors.foreground.neutralMuted};
  font-size: 12px;
  line-height: 20px;
  word-break: keep-all;
`;
const accessibleTextStyle = css`
  position: absolute;
  width: 1px;
  height: 1px;
  overflow: hidden;
  clip-path: inset(50%);
  white-space: nowrap;
`;
