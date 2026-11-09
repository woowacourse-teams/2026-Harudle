import { css, keyframes } from '@emotion/react';
import { useEffect, useRef, useState, type RefObject } from 'react';
import { createPortal } from 'react-dom';
import { theme } from '../../../styles/theme';
import { GUEST_TRIAL_COPY } from './copy';

interface SpotlightRect {
  readonly top: number;
  readonly left: number;
  readonly size: number;
}

interface HighlightRect {
  readonly top: number;
  readonly left: number;
  readonly width: number;
  readonly height: number;
}

type GuideStep = 1 | 2 | 3;

interface GuestTrialGuideProps {
  readonly isOpen: boolean;
  readonly anchorRef: RefObject<HTMLButtonElement | null>;
  readonly onStart: () => void;
  readonly onClose: () => void;
}

const TOTAL_STEPS = 3;
// 피드 코드를 바꾸지 않고 기존 카테고리 목록을 찾는다.
const CATEGORY_LIST_SELECTOR = 'ul[aria-label="카테고리"]';
const CATEGORY_HIGHLIGHT_PADDING = 6;
const CATEGORY_TEXT_GAP = 64;
const CATEGORY_NUDGE_PX = 72;
const CATEGORY_NUDGE_DELAY_MS = 450;
const CATEGORY_NUDGE_RETURN_MS = 900;

const SPOTLIGHT_PADDING = 1;
const TEXT_GAP = 72;
const ARROW_TIP_GAP = 12;
const ARROW_TAIL_GAP = 8;
const ARROW_WIDTH = 52;
const ARROW_TIP_X = 12;

const measureCategoryList = (): HighlightRect | null => {
  const list = document.querySelector(CATEGORY_LIST_SELECTOR);
  if (!list) return null;

  const rect = list.getBoundingClientRect();
  return {
    top: rect.top - CATEGORY_HIGHLIGHT_PADDING,
    left: rect.left - CATEGORY_HIGHLIGHT_PADDING,
    width: rect.width + CATEGORY_HIGHLIGHT_PADDING * 2,
    height: rect.height + CATEGORY_HIGHLIGHT_PADDING * 2,
  };
};

const prefersReducedMotion = (): boolean => {
  return (
    window.matchMedia?.('(prefers-reduced-motion: reduce)').matches ?? false
  );
};

const measureAnchor = (anchor: HTMLElement | null): SpotlightRect | null => {
  if (!anchor) return null;

  const rect = anchor.getBoundingClientRect();
  const size = Math.max(rect.width, rect.height) + SPOTLIGHT_PADDING * 2;
  return {
    top: rect.top + rect.height / 2 - size / 2,
    left: rect.left + rect.width / 2 - size / 2,
    size,
  };
};

/**
 * 첫 방문 안내. 1단계는 체험용 +, 2단계는 좌우로 넘기는 카테고리 목록을 밝게 남긴다.
 */
const GuestTrialGuide = ({
  isOpen,
  anchorRef,
  onStart,
  onClose,
}: GuestTrialGuideProps) => {
  const dialogRef = useRef<HTMLDialogElement>(null);
  const [step, setStep] = useState<GuideStep>(1);
  const [spotlight, setSpotlight] = useState<SpotlightRect | null>(null);
  const [categoryRect, setCategoryRect] = useState<HighlightRect | null>(null);

  useEffect((): (() => void) | void => {
    const dialog = dialogRef.current;
    if (!dialog) return;
    if (!isOpen) {
      if (dialog.open) dialog.close();
      return;
    }

    const updateHighlights = (): void => {
      setSpotlight(measureAnchor(anchorRef.current));
      setCategoryRect(measureCategoryList());
    };
    updateHighlights();
    if (!dialog.open) dialog.showModal();
    window.addEventListener('resize', updateHighlights);

    return (): void => {
      window.removeEventListener('resize', updateHighlights);
    };
  }, [isOpen, anchorRef, step]);

  // 2단계에서 실제 목록을 한 번 살짝 넘겼다 돌려 좌우로 움직인다는 것을 보여준다.
  useEffect((): (() => void) | void => {
    if (!isOpen || step !== 2 || prefersReducedMotion()) return;
    const list = document.querySelector(CATEGORY_LIST_SELECTOR);
    if (!list || list.scrollWidth <= list.clientWidth) return;

    const startLeft = list.scrollLeft;
    const nudgeId = setTimeout((): void => {
      list.scrollTo({
        left: startLeft + CATEGORY_NUDGE_PX,
        behavior: 'smooth',
      });
    }, CATEGORY_NUDGE_DELAY_MS);
    const returnId = setTimeout((): void => {
      list.scrollTo({ left: startLeft, behavior: 'smooth' });
    }, CATEGORY_NUDGE_DELAY_MS + CATEGORY_NUDGE_RETURN_MS);

    return (): void => {
      clearTimeout(nudgeId);
      clearTimeout(returnId);
    };
  }, [isOpen, step]);

  const spotlightBottom = spotlight ? spotlight.top + spotlight.size : 0;
  const textTop = spotlight ? spotlightBottom + TEXT_GAP : 140;
  const categoryTextTop = categoryRect
    ? categoryRect.top + categoryRect.height + CATEGORY_TEXT_GAP
    : 140;

  return createPortal(
    <dialog
      ref={dialogRef}
      aria-label={GUEST_TRIAL_COPY.guideLabel}
      css={dialogStyle}
      onCancel={(event): void => {
        event.preventDefault();
        onClose();
      }}
    >
      {step === 1 && (
        <>
          {spotlight && (
            <button
              type="button"
              aria-label={GUEST_TRIAL_COPY.guideSpotlightAction}
              css={spotlightStyle}
              style={{
                top: spotlight.top,
                left: spotlight.left,
                width: spotlight.size,
                height: spotlight.size,
              }}
              onClick={onStart}
            />
          )}
          {spotlight && (
            <GuideArrow
              tipX={spotlight.left + spotlight.size / 2}
              tipY={spotlightBottom + ARROW_TIP_GAP}
              tailY={textTop - ARROW_TAIL_GAP}
            />
          )}
          <section css={guideTextStyle} style={{ top: textTop }}>
            <span css={badgeStyle}>{GUEST_TRIAL_COPY.guideBadge}</span>
            <h2 css={titleStyle}>{GUEST_TRIAL_COPY.guideTitle}</h2>
            <p css={descriptionStyle}>
              {GUEST_TRIAL_COPY.guideDescriptionPrefix}
              <strong>{GUEST_TRIAL_COPY.guideDescriptionEmphasis}</strong>
              {GUEST_TRIAL_COPY.guideDescriptionSuffix}
            </p>
            <GuideFooter
              step={1}
              actionLabel={GUEST_TRIAL_COPY.guideNext}
              onAction={(): void => setStep(2)}
            />
          </section>
        </>
      )}
      {step === 2 && (
        <>
          {categoryRect ? (
            <div
              aria-hidden="true"
              css={categoryHighlightStyle}
              style={{
                top: categoryRect.top,
                left: categoryRect.left,
                width: categoryRect.width,
                height: categoryRect.height,
              }}
            />
          ) : (
            <div aria-hidden="true" css={dimStyle} />
          )}
          {categoryRect && (
            <SwipeHint
              top={categoryRect.top + categoryRect.height + 14}
              centerX={categoryRect.left + categoryRect.width / 2}
            />
          )}
          <section css={guideTextStyle} style={{ top: categoryTextTop }}>
            <h2 css={titleStyle}>{GUEST_TRIAL_COPY.categoryGuideTitle}</h2>
            <GuideFooter
              step={2}
              actionLabel={GUEST_TRIAL_COPY.guideNext}
              onAction={(): void => setStep(3)}
            />
          </section>
        </>
      )}
      {step === 3 && (
        <>
          {spotlight && (
            <button
              type="button"
              aria-label={GUEST_TRIAL_COPY.guideSpotlightAction}
              css={simpleSpotlightStyle}
              style={{
                top: spotlight.top,
                left: spotlight.left,
                width: spotlight.size,
                height: spotlight.size,
              }}
              onClick={onStart}
            />
          )}
          <section css={guideTextStyle} style={{ top: spotlightBottom + 28 }}>
            <h2 css={titleStyle}>{GUEST_TRIAL_COPY.startGuideTitle}</h2>
            <GuideFooter
              step={3}
              actionLabel={GUEST_TRIAL_COPY.guideStart}
              onAction={onStart}
              secondaryAction={{
                label: GUEST_TRIAL_COPY.guideBrowseFirst,
                onClick: onClose,
              }}
            />
          </section>
        </>
      )}
    </dialog>,
    document.body,
  );
};

interface GuideFooterProps {
  readonly step: GuideStep;
  readonly actionLabel: string;
  readonly onAction: () => void;
  readonly secondaryAction?: {
    readonly label: string;
    readonly onClick: () => void;
  };
}

const GuideFooter = ({
  step,
  actionLabel,
  onAction,
  secondaryAction,
}: GuideFooterProps) => {
  return (
    <div css={footerStyle}>
      <span css={stepStyle}>
        {GUEST_TRIAL_COPY.guideStep(step, TOTAL_STEPS)}
      </span>
      <button type="button" css={stepButtonStyle} onClick={onAction}>
        {actionLabel}
      </button>
      {secondaryAction && (
        <button
          type="button"
          css={secondaryButtonStyle}
          onClick={secondaryAction.onClick}
        >
          {secondaryAction.label}
        </button>
      )}
    </div>
  );
};

interface SwipeHintProps {
  readonly top: number;
  readonly centerX: number;
}

/** 손가락이 좌우로 오가는 듯한 점과 양쪽 화살표로 넘길 수 있음을 알린다. */
const SwipeHint = ({ top, centerX }: SwipeHintProps) => {
  return (
    <div aria-hidden="true" css={swipeHintStyle} style={{ top, left: centerX }}>
      <span css={swipeChevronStyle}>‹</span>
      <span css={swipeTrackStyle}>
        <span css={swipeDotStyle} />
      </span>
      <span css={swipeChevronStyle}>›</span>
    </div>
  );
};

interface GuideArrowProps {
  readonly tipX: number;
  readonly tipY: number;
  readonly tailY: number;
}

/** 안내 문구에서 + 중심까지 이어지는 화살표. 끝점을 + 위치에서 계산해 어긋나지 않게 한다. */
const GuideArrow = ({ tipX, tipY, tailY }: GuideArrowProps) => {
  const height = Math.max(tailY - tipY, 24);
  const tailX = ARROW_WIDTH - 10;

  return (
    <svg
      aria-hidden="true"
      css={arrowStyle}
      width={ARROW_WIDTH}
      height={height}
      viewBox={`0 0 ${ARROW_WIDTH} ${height}`}
      style={{ top: tipY, left: tipX - ARROW_TIP_X }}
    >
      <path
        css={arrowLineStyle}
        pathLength={1}
        d={`M ${tailX} ${height - 2} C ${tailX} ${height * 0.35}, ${ARROW_TIP_X + 2} ${height * 0.5}, ${ARROW_TIP_X} 2`}
      />
      <path
        css={arrowHeadStyle}
        d={`M ${ARROW_TIP_X - 7} 10 L ${ARROW_TIP_X} 2 L ${ARROW_TIP_X + 7} 10`}
      />
    </svg>
  );
};

export default GuestTrialGuide;

const auraPulse = keyframes`
  from {
    opacity: 0.75;
    transform: scale(1);
  }
  to {
    opacity: 0;
    transform: scale(1.8);
  }
`;

const arrowDraw = keyframes`
  from {
    stroke-dashoffset: 1;
  }
  to {
    stroke-dashoffset: 0;
  }
`;

const arrowHeadAppear = keyframes`
  from {
    opacity: 0;
    transform: translateY(4px);
  }
  to {
    opacity: 1;
    transform: translateY(0);
  }
`;

const arrowBob = keyframes`
  0%, 100% {
    transform: translateY(0);
  }
  50% {
    transform: translateY(-5px);
  }
`;

const dialogStyle = css`
  position: fixed;
  inset: 0;
  width: 100%;
  max-width: none;
  height: 100%;
  max-height: none;
  margin: 0;
  padding: 0;
  border: 0;
  background: transparent;
  overflow: hidden;

  &::backdrop {
    background: transparent;
  }
`;

const spotlightStyle = css`
  position: fixed;
  padding: 0;
  border: 2px solid ${theme.colors.foreground.onBrand};
  border-radius: 50%;
  background: transparent;
  box-shadow:
    0 0 0 6px rgb(189 165 255 / 33%),
    0 0 18px 6px rgb(189 165 255 / 35%),
    0 0 0 200vmax rgb(19 15 29 / 72%);
  cursor: pointer;

  &::before,
  &::after {
    content: '';
    position: absolute;
    inset: -2px;
    border: 2px solid rgb(207 188 255);
    border-radius: 50%;
    pointer-events: none;
    animation: ${auraPulse} 2.4s cubic-bezier(0.22, 1, 0.36, 1) infinite;
  }

  &::after {
    animation-delay: 1.2s;
  }

  &:focus-visible {
    outline: 2px solid ${theme.colors.stroke.focusRing};
    outline-offset: 4px;
  }

  @media (prefers-reduced-motion: reduce) {
    &::before,
    &::after {
      display: none;
    }
  }
`;

const arrowStyle = css`
  position: fixed;
  overflow: visible;
  color: rgb(238 231 255);
  animation: ${arrowBob} 1.6s ease-in-out 0.9s infinite;

  @media (prefers-reduced-motion: reduce) {
    animation: none;
  }
`;

const arrowLineStyle = css`
  fill: none;
  stroke: currentColor;
  stroke-width: 2;
  stroke-linecap: round;
  stroke-dasharray: 1;
  stroke-dashoffset: 1;
  animation: ${arrowDraw} 600ms ease-out 150ms forwards;

  @media (prefers-reduced-motion: reduce) {
    stroke-dashoffset: 0;
    animation: none;
  }
`;

const arrowHeadStyle = css`
  fill: none;
  stroke: currentColor;
  stroke-width: 2;
  stroke-linecap: round;
  stroke-linejoin: round;
  opacity: 0;
  animation: ${arrowHeadAppear} 220ms ease-out 700ms forwards;

  @media (prefers-reduced-motion: reduce) {
    opacity: 1;
    animation: none;
  }
`;

const guideTextStyle = css`
  position: fixed;
  bottom: 16px;
  overflow-y: auto;
  left: max(29px, calc((100vw - 430px) / 2 + 29px));
  right: max(25px, calc((100vw - 430px) / 2 + 25px));
  color: ${theme.colors.foreground.onBrand};
  letter-spacing: -0.35px;
`;

const badgeStyle = css`
  display: inline-block;
  margin-bottom: 16px;
  padding: 5px 12px;
  border-radius: 20px;
  background: ${theme.colors.background.brandSolid};
  color: ${theme.colors.foreground.onBrand};
  box-shadow: 0 0 14px rgb(189 165 255 / 45%);
  font-size: 12px;
  font-weight: 600;
  line-height: 18px;
`;

const titleStyle = css`
  margin: 0;
  font-size: 25px;
  font-weight: 700;
  line-height: 1.45;
  white-space: pre-line;
  word-break: keep-all;
`;

const descriptionStyle = css`
  margin-top: 12px;
  color: rgb(240 235 247);
  font-size: 14px;
  line-height: 1.8;
  white-space: pre-line;
  word-break: keep-all;

  strong {
    color: rgb(207 188 255);
    font-weight: 700;
  }
`;

const swipeSlide = keyframes`
  0%, 100% {
    transform: translateX(-16px);
  }
  50% {
    transform: translateX(16px);
  }
`;

const highlightPulse = keyframes`
  0%, 100% {
    opacity: 0.35;
  }
  50% {
    opacity: 1;
  }
`;

const dimStyle = css`
  position: fixed;
  inset: 0;
  background: rgb(19 15 29 / 72%);
`;

const categoryHighlightStyle = css`
  position: fixed;
  border: 2px solid rgb(207 188 255 / 55%);
  border-radius: 14px;
  box-shadow:
    0 0 0 6px rgb(189 165 255 / 25%),
    0 0 18px 6px rgb(189 165 255 / 30%),
    0 0 0 200vmax rgb(19 15 29 / 72%);
  pointer-events: none;

  /* 전체를 덮는 그림자는 고정하고, 밝게 깜빡이는 테두리만 따로 움직인다. */
  &::after {
    content: '';
    position: absolute;
    inset: -2px;
    border: 2px solid rgb(255 255 255);
    border-radius: inherit;
    animation: ${highlightPulse} 1.8s ease-in-out infinite;
  }

  @media (prefers-reduced-motion: reduce) {
    &::after {
      animation: none;
      opacity: 0.6;
    }
  }
`;

const swipeHintStyle = css`
  position: fixed;
  display: flex;
  align-items: center;
  gap: 6px;
  color: rgb(238 231 255);
  transform: translateX(-50%);
`;

const swipeChevronStyle = css`
  font-size: 20px;
  line-height: 1;
`;

const swipeTrackStyle = css`
  position: relative;
  display: grid;
  place-items: center;
  width: 56px;
  height: 6px;
  border-radius: 3px;
  background: rgb(255 255 255 / 22%);
`;

const swipeDotStyle = css`
  width: 14px;
  height: 14px;
  border-radius: 50%;
  background: ${theme.colors.foreground.onBrand};
  box-shadow: 0 0 10px rgb(207 188 255 / 80%);
  animation: ${swipeSlide} 1.6s ease-in-out infinite;

  @media (prefers-reduced-motion: reduce) {
    animation: none;
  }
`;

const footerStyle = css`
  display: flex;
  align-items: center;
  gap: 16px;
  margin-top: 24px;
`;

const stepStyle = css`
  color: rgb(231 224 239 / 80%);
  font-size: 13px;
  font-variant-numeric: tabular-nums;
`;

const stepButtonStyle = css`
  min-width: 72px;
  min-height: 44px;
  padding: 10px 20px;
  border: 1px solid rgb(255 255 255 / 30%);
  border-radius: 999px;
  background: rgb(255 255 255 / 14%);
  color: ${theme.colors.foreground.onBrand};
  font-size: 14px;
  font-weight: 600;
  cursor: pointer;

  &:focus-visible {
    outline: 2px solid ${theme.colors.stroke.focusRing};
    outline-offset: 3px;
  }
`;

// 마지막 단계는 이미 본 +를 다시 가리키므로 퍼지는 링 없이 테두리와 빛만 둔다.
const simpleSpotlightStyle = css`
  position: fixed;
  padding: 0;
  border: 2px solid ${theme.colors.foreground.onBrand};
  border-radius: 50%;
  background: transparent;
  box-shadow:
    0 0 0 6px rgb(189 165 255 / 33%),
    0 0 18px 6px rgb(189 165 255 / 35%),
    0 0 0 200vmax rgb(19 15 29 / 72%);
  cursor: pointer;

  &:focus-visible {
    outline: 2px solid ${theme.colors.stroke.focusRing};
    outline-offset: 4px;
  }
`;

const secondaryButtonStyle = css`
  min-height: 44px;
  padding: 4px 2px;
  border: 0;
  border-bottom: 1px solid rgb(255 255 255 / 38%);
  background: transparent;
  color: rgb(231 224 239);
  font-size: 13px;
  cursor: pointer;
`;
