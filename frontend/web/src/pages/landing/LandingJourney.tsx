/** @jsxImportSource @emotion/react */
import { css } from '@emotion/react';
import type { JSX } from 'react';
import { LANDING_JOURNEY } from './copy';

const LandingJourney = (): JSX.Element => (
  <section css={sectionStyle} aria-labelledby="landing-journey-title">
    <div css={innerStyle}>
      <div css={introStyle} data-reveal="journey-intro">
        <h2 id="landing-journey-title">{LANDING_JOURNEY.title}</h2>
        <p>{LANDING_JOURNEY.description}</p>
      </div>
      <ol css={listStyle} aria-label="하루들의 가설 검증 과정">
        {LANDING_JOURNEY.steps.map((step, index) => (
          <li key={step.title} css={stepStyle} data-reveal="journey-step">
            <span css={periodStyle}>
              {String(index + 1).padStart(2, '0')} · {step.period}
            </span>
            <h3>{step.title}</h3>
            <p>{step.description}</p>
            <dl css={metricStyle}>
              <dt>{step.metric.label}</dt>
              <dd>{step.metric.value}</dd>
            </dl>
          </li>
        ))}
      </ol>
    </div>
  </section>
);

export default LandingJourney;

const sectionStyle = css`
  padding: clamp(88px, 10vw, 140px) clamp(24px, 5vw, 72px) 0;
  background: #fff;
  @media (max-width: 767px) {
    padding: 80px 20px 0;
  }
`;
const innerStyle = css`
  width: min(100%, 1180px);
  margin: 0 auto;
`;
const introStyle = css`
  h2 {
    margin: 0;
    color: #1c1530;
    font-size: clamp(34px, 4.5vw, 58px);
    font-weight: 800;
    line-height: 1.24;
    letter-spacing: -0.05em;
    white-space: pre-line;
    word-break: keep-all;
  }
  p {
    margin: 20px 0 0;
    color: #5f566b;
    font-size: 16px;
    line-height: 1.7;
    white-space: pre-line;
    word-break: keep-all;
  }
  @media (max-width: 767px) {
    h2 {
      font-size: clamp(30px, 8vw, 40px);
    }
    p {
      white-space: normal;
    }
  }
`;
const listStyle = css`
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 14px;
  margin: clamp(36px, 5vw, 60px) 0 0;
  padding: 0;
  list-style: none;
  @media (max-width: 900px) {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
  @media (max-width: 560px) {
    grid-template-columns: minmax(0, 1fr);
    gap: 10px;
  }
`;
const stepStyle = css`
  display: flex;
  flex-direction: column;
  min-width: 0;
  padding: 26px 24px 22px;
  border: 1px solid #eee9f7;
  border-radius: 24px;
  background: #fbfaff;
  h3 {
    margin: 12px 0 0;
    color: #1c1530;
    font-size: 19px;
    font-weight: 800;
    line-height: 1.4;
    letter-spacing: -0.03em;
    word-break: keep-all;
  }
  p {
    margin: 10px 0 0;
    color: #5f566b;
    font-size: 15px;
    line-height: 1.7;
    word-break: keep-all;
  }
  @media (max-width: 560px) {
    padding: 22px 20px 20px;
    border-radius: 20px;
  }
`;
const periodStyle = css`
  color: #7355da;
  font-size: 13px;
  font-weight: 800;
  line-height: 18px;
`;
const metricStyle = css`
  display: flex;
  flex-direction: column-reverse;
  margin: auto 0 0;
  padding-top: 18px;
  dd {
    margin: 0;
    color: #6545cb;
    font-size: 24px;
    font-weight: 800;
    line-height: 1.3;
    letter-spacing: -0.03em;
  }
  dt {
    margin-top: 2px;
    color: #81798b;
    font-size: 13px;
    line-height: 1.5;
    word-break: keep-all;
  }
`;
