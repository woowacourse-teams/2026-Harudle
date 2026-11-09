/** @jsxImportSource @emotion/react */
import { css } from '@emotion/react';
import { useEffect, useRef, useState, type JSX, type RefObject } from 'react';
import { LANDING_COPY, LANDING_EXAMPLES } from './copy';
import LandingExampleDemo from './LandingExampleDemo';

interface LandingShowcaseProps {
  readonly pageRef: RefObject<HTMLElement | null>;
}

const LandingShowcase = ({ pageRef }: LandingShowcaseProps): JSX.Element => {
  const [activeExample, setActiveExample] = useState(0);
  const sectionRef = useRef<HTMLElement>(null);
  const stickyRef = useRef<HTMLDivElement>(null);
  const example = LANDING_EXAMPLES[activeExample];

  useEffect(() => {
    const page = pageRef.current;
    const section = sectionRef.current;
    const sticky = stickyRef.current;
    if (!page || !section || !sticky) return;

    const update = (): void => {
      const pageRect = page.getBoundingClientRect();
      const sectionRect = section.getBoundingClientRect();
      const stickyTop = Math.min(0, page.clientHeight - sticky.offsetHeight);
      section.style.setProperty('--showcase-sticky-top', `${stickyTop}px`);
      section.style.setProperty(
        '--showcase-height',
        `${sticky.offsetHeight}px`,
      );
      const travel = section.offsetHeight - sticky.offsetHeight;
      if (travel <= 0) return;
      const progress = (pageRect.top + stickyTop - sectionRect.top) / travel;
      if (progress < 0 || progress > 1) return;
      setActiveExample(Math.round(progress * (LANDING_EXAMPLES.length - 1)));
    };
    const observer =
      typeof ResizeObserver === 'undefined' ? null : new ResizeObserver(update);
    observer?.observe(page);
    observer?.observe(sticky);
    page.addEventListener('scroll', update, { passive: true });
    update();
    return (): void => {
      observer?.disconnect();
      page.removeEventListener('scroll', update);
    };
  }, [pageRef]);

  const selectExample = (index: number): void => {
    setActiveExample(index);
    const page = pageRef.current;
    const section = sectionRef.current;
    const sticky = stickyRef.current;
    if (!page || !section || !sticky) return;
    const travel = section.offsetHeight - sticky.offsetHeight;
    if (travel <= 0) return;
    const stickyTop = Math.min(0, page.clientHeight - sticky.offsetHeight);
    const sectionTop =
      page.scrollTop +
      section.getBoundingClientRect().top -
      page.getBoundingClientRect().top;
    page.scrollTo({
      top:
        sectionTop -
        stickyTop +
        (travel * index) / (LANDING_EXAMPLES.length - 1),
      behavior: 'instant',
    });
  };

  return (
    <section
      id="examples"
      ref={sectionRef}
      css={sectionStyle}
      aria-labelledby="landing-example-title"
    >
      <div ref={stickyRef} css={stickyStyle}>
        <div css={innerStyle}>
          <div css={introStyle}>
            <div data-reveal="showcase-intro">
              <h2 id="landing-example-title">{LANDING_COPY.examples.title}</h2>
            </div>
            <div css={choiceAreaStyle} data-reveal="showcase-choices">
              <div
                css={choicesStyle}
                role="group"
                aria-label="네컷만화 예시 선택"
              >
                {LANDING_EXAMPLES.map((item, index) => (
                  <button
                    key={item.id}
                    type="button"
                    css={choiceStyle}
                    aria-pressed={activeExample === index}
                    aria-controls="landing-example"
                    onClick={() => selectExample(index)}
                  >
                    {item.label}
                  </button>
                ))}
              </div>
            </div>
          </div>
          <div css={demoWrapStyle} data-reveal="showcase-demo">
            <LandingExampleDemo
              key={example.id}
              example={example}
              pageRef={pageRef}
            />
          </div>
        </div>
      </div>
    </section>
  );
};

export default LandingShowcase;

const sectionStyle = css`
  position: relative;
  min-height: calc(var(--showcase-height, 100svh) + 200svh);
  background: #f5f1ff;
  color: #1c1530;
`;
const stickyStyle = css`
  position: sticky;
  top: var(--showcase-sticky-top, 0px);
  display: grid;
  align-items: center;
  min-height: 100svh;
  padding: 104px 28px 116px;
  @media (max-width: 767px) {
    padding: 78px 20px 84px;
  }
`;
const innerStyle = css`
  display: grid;
  grid-template-columns: minmax(0, 0.82fr) minmax(0, 1.18fr);
  align-items: center;
  gap: clamp(52px, 7vw, 108px);
  width: min(100%, 1180px);
  margin: 0 auto;
  @media (max-width: 767px) {
    grid-template-columns: 1fr;
    gap: 38px;
  }
`;
const introStyle = css`
  position: relative;
  z-index: 1;
  min-width: 0;
  h2 {
    margin: 0;
    color: #1c1530;
    font-size: clamp(36px, 4.5vw, 56px);
    font-weight: 800;
    line-height: 1.24;
    letter-spacing: -0.055em;
    white-space: pre-line;
    word-break: keep-all;
  }
  @media (max-width: 767px) {
    h2 {
      font-size: clamp(30px, 8vw, 39px);
      line-height: 1.28;
    }
  }
`;
const choiceAreaStyle = css`
  margin-top: 36px;
  @media (max-width: 767px) {
    margin-top: 26px;
  }
`;
const choicesStyle = css`
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
`;
const choiceStyle = css`
  display: inline-flex;
  align-items: center;
  justify-content: center;
  min-height: 48px;
  min-width: 106px;
  padding: 10px 20px;
  border: 1px solid #e0d8f2;
  border-radius: 16px;
  background: rgb(255 255 255 / 75%);
  color: #655a77;
  font-size: 13px;
  font-weight: 700;
  line-height: 20px;
  cursor: pointer;
  transition:
    background-color 180ms ease,
    border-color 180ms ease,
    color 180ms ease,
    transform 180ms ease;
  &[aria-pressed='true'] {
    border-color: #7355da;
    background: #7355da;
    color: #fff;
  }
  &:focus-visible {
    outline: 3px solid rgb(115 85 218 / 35%);
    outline-offset: 3px;
  }
  @media (hover: hover) and (prefers-reduced-motion: no-preference) {
    &:hover {
      transform: translateY(-3px);
      border-color: #bdaaf4;
    }
  }
  @media (max-width: 370px) {
    flex: 1 1 0;
    min-width: 0;
    padding-right: 10px;
    padding-left: 10px;
  }
  @media (prefers-reduced-motion: reduce) {
    transition: none;
  }
`;
const demoWrapStyle = css`
  position: relative;
  min-width: 0;
`;
