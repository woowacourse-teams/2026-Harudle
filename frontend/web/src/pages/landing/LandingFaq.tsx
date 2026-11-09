/** @jsxImportSource @emotion/react */
import { css } from '@emotion/react';
import type { JSX } from 'react';

interface FaqItem {
  readonly question: string;
  readonly answer: string;
}

const faqItems: readonly FaqItem[] = [
  {
    question: '하루들에서는 어떻게 만화를 만드나요?',
    answer:
      '기억하고 싶은 순간을 글로 적으면 AI가 이야기를 네컷만화로 만들어줘요.',
  },
  {
    question: '사진이 꼭 있어야 하나요?',
    answer: '아니요. 사진이 없어도 괜찮아요. 함께한 순간을 글로 적어 주세요.',
  },
  {
    question: '내가 쓴 일기는 다른 사람에게 공개되나요?',
    answer:
      '일기 원문은 다른 이용자에게 공개되지 않아요. 직접 공개 피드에 올린 네컷만화는 다른 사람도 볼 수 있어요.',
  },
  {
    question: '로그인 없이도 볼 수 있나요?',
    answer:
      '네. 공개 피드는 로그인 없이 구경할 수 있어요. 로그인하면 내 이야기를 기록하고 모아볼 수 있어요.',
  },
  {
    question: '만든 만화를 저장하거나 공유할 수 있나요?',
    answer:
      '네. 완성한 네컷만화를 이미지로 저장하거나 원하는 만화를 공개 피드에 올릴 수 있어요.',
  },
  {
    question: '일기를 삭제할 수 있나요?',
    answer:
      '네. 내 기록에서 일기를 삭제할 수 있어요. 공개 피드에 올린 만화는 피드에서만 따로 내릴 수도 있어요.',
  },
  {
    question: 'AI가 내 이야기를 처리하나요?',
    answer:
      '네컷만화를 만들기 위해 입력한 내용이 AI 제공사에 전달되어 처리돼요. 자세한 내용은 개인정보처리방침에서 확인해 주세요.',
  },
];

const LandingFaq = (): JSX.Element => (
  <section css={sectionStyle} aria-labelledby="landing-faq-title">
    <div css={innerStyle}>
      <div css={headingStyle} data-reveal="faq-intro">
        <h2 id="landing-faq-title">
          자주 묻는
          <br />
          질문
        </h2>
        <a css={contactStyle} href="mailto:harudle.official@gmail.com">
          하루들에 문의하기{' '}
          <svg
            width="15"
            height="15"
            viewBox="0 0 16 16"
            fill="none"
            aria-hidden="true"
          >
            <path
              d="m6 4 4 4-4 4"
              stroke="currentColor"
              strokeWidth="1.8"
              strokeLinecap="round"
              strokeLinejoin="round"
            />
          </svg>
        </a>
      </div>
      <div css={listStyle}>
        {faqItems.map((item) => (
          <details key={item.question} css={itemStyle} data-reveal="faq-item">
            <summary>
              <span>{item.question}</span>
              <span css={toggleStyle} aria-hidden="true">
                <span />
                <span />
              </span>
            </summary>
            <div css={answerStyle}>
              <p>{item.answer}</p>
            </div>
          </details>
        ))}
      </div>
    </div>
  </section>
);

export default LandingFaq;

const sectionStyle = css`
  padding: clamp(88px, 10vw, 140px) clamp(24px, 5vw, 72px)
    clamp(100px, 11vw, 150px);
  background: #1c1530;
  color: #fff;
`;
const innerStyle = css`
  display: grid;
  grid-template-columns: minmax(0, 0.78fr) minmax(0, 1.22fr);
  gap: clamp(48px, 8vw, 112px);
  width: min(100%, 1200px);
  margin: 0 auto;
  padding-top: clamp(60px, 7vw, 96px);
  border-top: 1px solid rgb(227 217 255 / 15%);
  @media (max-width: 760px) {
    grid-template-columns: minmax(0, 1fr);
    gap: 28px;
    padding-top: 48px;
  }
`;
const headingStyle = css`
  h2 {
    margin: 0;
    color: #fff;
    font-size: clamp(34px, 4.7vw, 62px);
    font-weight: 800;
    line-height: 1.22;
    letter-spacing: -0.045em;
    word-break: keep-all;
  }
  @media (max-width: 760px) {
    h2 {
      font-size: clamp(32px, 8vw, 46px);
    }
    h2 br {
      display: none;
    }
  }
`;
const listStyle = css`
  border-top: 1px solid rgb(227 217 255 / 18%);
`;
const itemStyle = css`
  border-bottom: 1px solid rgb(227 217 255 / 18%);
  summary {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 18px;
    min-height: 82px;
    padding: 21px 2px;
    list-style: none;
    color: #f8f5ff;
    font-size: clamp(15px, 1.25vw, 17px);
    font-weight: 700;
    line-height: 1.55;
    word-break: keep-all;
    cursor: pointer;
  }
  summary::-webkit-details-marker {
    display: none;
  }
  summary:focus-visible {
    outline: 3px solid #bda9ff;
    outline-offset: 3px;
    border-radius: 6px;
  }
  &[open] summary {
    color: #d3c2ff;
  }
  &[open] summary > span:last-of-type {
    background: #8b6fe8;
    color: #fff;
  }
  &[open] summary > span:last-of-type > span:last-of-type {
    transform: translate(-50%, -50%);
  }
  @media (hover: hover) {
    summary:hover {
      color: #d3c2ff;
    }
  }
  @supports (interpolate-size: allow-keywords) {
    interpolate-size: allow-keywords;
    &::details-content {
      block-size: 0;
      overflow: clip;
      transition:
        block-size 250ms ease,
        content-visibility 250ms allow-discrete;
    }
    &[open]::details-content {
      block-size: auto;
    }
  }
  @media (max-width: 760px) {
    summary {
      min-height: 72px;
      padding: 18px 2px;
    }
  }
  @media (prefers-reduced-motion: reduce) {
    &::details-content {
      transition: none;
    }
  }
`;
const toggleStyle = css`
  position: relative;
  flex: 0 0 34px;
  width: 34px;
  height: 34px;
  border: 1px solid rgb(227 217 255 / 20%);
  border-radius: 50%;
  background: rgb(255 255 255 / 5%);
  color: #d7c8ff;
  transition:
    background-color 200ms ease,
    color 200ms ease;
  span {
    position: absolute;
    top: 50%;
    left: 50%;
    width: 12px;
    height: 1.5px;
    border-radius: 2px;
    background: currentColor;
    transform: translate(-50%, -50%);
    transition: transform 200ms ease;
  }
  span:last-of-type {
    transform: translate(-50%, -50%) rotate(90deg);
  }
  @media (prefers-reduced-motion: reduce) {
    &,
    span {
      transition: none;
    }
  }
`;
const answerStyle = css`
  padding: 0 52px 26px 2px;
  color: #c5bbd6;
  font-size: 15px;
  line-height: 1.8;
  word-break: keep-all;
  animation: faq-answer-in 200ms ease-out both;
  @keyframes faq-answer-in {
    from {
      opacity: 0;
      transform: translateY(-4px);
    }
    to {
      opacity: 1;
      transform: none;
    }
  }
  @media (max-width: 760px) {
    padding: 0 38px 22px 2px;
    font-size: 15px;
  }
  @media (prefers-reduced-motion: reduce) {
    animation: none;
  }
`;
const contactStyle = css`
  display: inline-flex;
  align-items: center;
  gap: 4px;
  margin-top: 32px;
  color: #e3d9ff;
  font-size: 15px;
  font-weight: 700;
  line-height: 1.8;
  text-decoration: none;
  &:hover {
    color: #fff;
  }
  &:focus-visible {
    outline: 3px solid #bda9ff;
    outline-offset: 4px;
    border-radius: 4px;
  }
  @media (max-width: 760px) {
    margin-top: 18px;
  }
`;
