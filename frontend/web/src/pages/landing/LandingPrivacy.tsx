/** @jsxImportSource @emotion/react */
import { css } from '@emotion/react';
import type { JSX } from 'react';
import { theme } from '../../styles/theme';

type IllustrationKind = 'record' | 'backup' | 'share';

interface PrivacyItem {
  readonly kind: IllustrationKind;
  readonly title: string;
  readonly description: string;
}

const privacyItems: readonly PrivacyItem[] = [
  {
    kind: 'record',
    title: '일기 원문은\n다른 이용자에게 비공개',
    description: '입력한 내용은 암호화된 연결로 전달돼요.',
  },
  {
    kind: 'backup',
    title: '소중한 네컷을\n별도 저장소에 백업',
    description: '생성된 그림 원본을 Cloudflare R2에 정기적으로 백업해요.',
  },
  {
    kind: 'share',
    title: '공개할 만화는\n직접 골라요',
    description: '피드에 올린 네컷만화는 다른 사람도 볼 수 있어요.',
  },
];

function PrivacyIllustration({
  kind,
}: {
  readonly kind: IllustrationKind;
}): JSX.Element {
  if (kind === 'record') {
    return (
      <svg viewBox="0 0 120 108" fill="none" aria-hidden="true">
        <path
          d="M24 18c0-4 3-7 7-7h49c4 0 7 3 7 7v67c0 4-3 7-7 7H31c-4 0-7-3-7-7V18Z"
          fill="white"
          stroke="#22202C"
          strokeWidth="2.5"
        />
        <path
          d="M35 11v81M45 27h28M45 36h19M45 46h24"
          stroke="#8B6FE8"
          strokeWidth="3"
          strokeLinecap="round"
        />
        <path
          d="M12 34l4 3m-2-13 2 4m78-11 3-4"
          stroke="#B7A4F4"
          strokeWidth="2.5"
          strokeLinecap="round"
        />
        <rect
          x="61"
          y="57"
          width="35"
          height="29"
          rx="8"
          fill="#B9A3F7"
          stroke="#22202C"
          strokeWidth="2.5"
        />
        <path
          d="M69 57v-5a9.5 9.5 0 0 1 19 0v5"
          stroke="#22202C"
          strokeWidth="2.5"
          strokeLinecap="round"
        />
        <circle cx="78.5" cy="70" r="2.7" fill="#22202C" />
        <path
          d="M78.5 73v3.5"
          stroke="#22202C"
          strokeWidth="2"
          strokeLinecap="round"
        />
        <path
          d="M12 82c12 4 24 6 39 6"
          stroke="#DDD2FF"
          strokeWidth="5"
          strokeLinecap="round"
          opacity=".8"
        />
      </svg>
    );
  }
  if (kind === 'backup') {
    return (
      <svg viewBox="0 0 120 108" fill="none" aria-hidden="true">
        <path
          d="M26 24a7 7 0 0 1 7-7h50a7 7 0 0 1 7 7v58a7 7 0 0 1-7 7H33a7 7 0 0 1-7-7V24Z"
          fill="#D9CCFF"
          stroke="#22202C"
          strokeWidth="2.5"
        />
        <path
          d="M18 30a7 7 0 0 1 7-7h50a7 7 0 0 1 7 7v58a7 7 0 0 1-7 7H25a7 7 0 0 1-7-7V30Z"
          fill="white"
          stroke="#22202C"
          strokeWidth="2.5"
        />
        <path
          d="M27 32h46v49H27zM50 32v49M27 56h46"
          stroke="#8B6FE8"
          strokeWidth="2.2"
          strokeLinejoin="round"
        />
        <circle cx="38" cy="43" r="3" fill="#C2AFFF" />
        <circle cx="61" cy="68" r="3" fill="#C2AFFF" />
        <path
          d="M87 39c1-6 11-6 12 0 7 0 9 10 3 13H84c-6-2-5-12 3-13Z"
          fill="#8B6FE8"
          stroke="#22202C"
          strokeWidth="2.5"
          strokeLinejoin="round"
        />
        <path
          d="m91 45 3 3 5-6"
          stroke="white"
          strokeWidth="2.2"
          strokeLinecap="round"
          strokeLinejoin="round"
        />
        <path
          d="M92 70v4m-2-2h4M13 20v4m-2-2h4"
          stroke="#B7A4F4"
          strokeWidth="2.5"
          strokeLinecap="round"
        />
      </svg>
    );
  }
  return (
    <svg viewBox="0 0 120 108" fill="none" aria-hidden="true">
      <path
        d="M19 24a11 11 0 0 1 11-11h50a11 11 0 0 1 11 11v33a11 11 0 0 1-11 11H46L31 79V68h-1a11 11 0 0 1-11-11V24Z"
        fill="white"
        stroke="#22202C"
        strokeWidth="2.5"
        strokeLinejoin="round"
      />
      <path
        d="M33 31h38M33 41h25M33 51h31"
        stroke="#B29AF3"
        strokeWidth="3"
        strokeLinecap="round"
      />
      <path
        d="M74 67c0-8 6-14 14-14h10c8 0 14 6 14 14v10c0 8-6 14-14 14h-4l-7 7v-7c-7-1-13-7-13-14V67Z"
        fill="#C9B7FC"
        stroke="#22202C"
        strokeWidth="2.5"
        strokeLinejoin="round"
      />
      <path
        d="M88 69c0-4 5-5 7-1 2-4 7-3 7 1 0 4-7 8-7 8s-7-4-7-8Z"
        fill="white"
      />
      <path
        d="M7 49h4m-2-2v4M98 21h4m-2-2v4"
        stroke="#B7A4F4"
        strokeWidth="2.5"
        strokeLinecap="round"
      />
    </svg>
  );
}

const LandingPrivacy = (): JSX.Element => (
  <section css={sectionStyle} aria-labelledby="landing-privacy-title">
    <div css={innerStyle}>
      <div css={introStyle} data-reveal="privacy-intro">
        <h2 id="landing-privacy-title">
          우리의 이야기를
          <br />
          <span>소중하게</span> 다뤄요
        </h2>
      </div>
      <div css={cardsStyle}>
        {privacyItems.map((item) => (
          <article
            key={item.kind}
            css={cardStyle}
            data-reveal="privacy-card"
            data-kind={item.kind}
          >
            <div css={illustrationStyle}>
              <div css={artGlowStyle} aria-hidden="true" />
              <PrivacyIllustration kind={item.kind} />
            </div>
            <div css={cardCopyStyle}>
              <h3>{item.title}</h3>
              <p>{item.description}</p>
            </div>
          </article>
        ))}
      </div>
      <div css={privacyNoteStyle} data-reveal="privacy-note">
        <p>
          네컷만화를 만드는 과정에서 입력한 일기 내용이 AI 제공사에 전달되어
          처리돼요.
        </p>
        <a href="/legal/privacy">
          개인정보처리방침 자세히 보기
          <svg
            width="16"
            height="16"
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
    </div>
  </section>
);

export default LandingPrivacy;

const sectionStyle = css`
  position: relative;
  overflow: hidden;
  padding: clamp(88px, 10vw, 144px) clamp(24px, 5vw, 72px) 0;
  background: #1c1530;
  color: #fff;
  &::before {
    position: absolute;
    top: -180px;
    right: -190px;
    width: min(560px, 85vw);
    height: min(560px, 85vw);
    border-radius: 50%;
    background: radial-gradient(
      circle,
      rgb(139 111 232 / 17%),
      transparent 66%
    );
    content: '';
    pointer-events: none;
  }
`;
const innerStyle = css`
  position: relative;
  width: min(100%, 1200px);
  margin: 0 auto;
`;
const introStyle = css`
  h2 {
    margin: 0;
    font-size: clamp(34px, 4.7vw, 64px);
    font-weight: 800;
    line-height: 1.23;
    letter-spacing: -0.045em;
    word-break: keep-all;
  }
  h2 span {
    color: #bda9ff;
  }
  @media (max-width: 760px) {
    h2 {
      font-size: clamp(32px, 8vw, 46px);
    }
  }
`;
const cardsStyle = css`
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 16px;
  margin-top: clamp(38px, 5vw, 68px);
  @media (max-width: 760px) {
    grid-template-columns: minmax(0, 1fr);
    gap: 12px;
  }
`;
const cardStyle = css`
  position: relative;
  display: grid;
  grid-template-rows: minmax(190px, 1fr) auto;
  min-height: 365px;
  overflow: hidden;
  padding: 22px clamp(22px, 2.3vw, 32px) 28px;
  border: 1px solid rgb(221 210 255 / 11%);
  border-radius: 28px;
  background: linear-gradient(150deg, #302743 0%, #292138 100%);
  &[data-kind='backup'] {
    background: linear-gradient(150deg, #382b51 0%, #2b223d 100%);
  }
  &[data-kind='share'] {
    background: linear-gradient(150deg, #30283d 0%, #29213a 100%);
  }
  @media (hover: hover) and (prefers-reduced-motion: no-preference) {
    &:hover svg {
      transform: translateY(-7px) rotate(-3deg);
    }
  }
  @media (max-width: 760px) {
    grid-template-columns: minmax(0, 1fr) minmax(94px, 36%);
    grid-template-rows: auto;
    align-items: center;
    gap: 6px;
    min-height: 180px;
    padding: 23px 15px 23px 22px;
    border-radius: 23px;
  }
  @media (max-width: 350px) {
    padding-left: 18px;
  }
`;
const illustrationStyle = css`
  position: relative;
  display: grid;
  grid-row: 1;
  place-items: center;
  min-width: 0;
  min-height: 190px;
  svg {
    position: relative;
    z-index: 1;
    display: block;
    width: min(100%, 190px);
    height: auto;
    filter: drop-shadow(0 16px 22px rgb(9 4 22 / 20%));
    transition: transform 350ms cubic-bezier(0.2, 0.7, 0.2, 1);
  }
  @media (max-width: 760px) {
    grid-column: 2;
    grid-row: 1;
    min-height: 118px;
    svg {
      width: min(100%, 140px);
    }
  }
  @media (prefers-reduced-motion: reduce) {
    svg {
      transition: none;
    }
  }
`;
const artGlowStyle = css`
  position: absolute;
  width: 76%;
  max-width: 220px;
  aspect-ratio: 1;
  border-radius: 50%;
  background: radial-gradient(circle, rgb(147 118 231 / 24%), transparent 70%);
`;
const cardCopyStyle = css`
  position: relative;
  z-index: 2;
  min-width: 0;
  h3 {
    margin: 0;
    color: #fff;
    font-size: clamp(19px, 1.8vw, 23px);
    font-weight: 800;
    line-height: 1.38;
    letter-spacing: -0.035em;
    white-space: pre-line;
    word-break: keep-all;
  }
  p {
    margin: 12px 0 0;
    color: #c8bfd5;
    font-size: 16px;
    line-height: 1.65;
    word-break: keep-all;
  }
  @media (max-width: 760px) {
    grid-column: 1;
    grid-row: 1;
    h3 {
      font-size: clamp(17px, 4.5vw, 20px);
    }
    p {
      font-size: 16px;
      line-height: 1.65;
    }
  }
`;
const privacyNoteStyle = css`
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: space-between;
  gap: 8px 24px;
  margin-top: 24px;
  color: #c5bbd6;
  p {
    font-size: 16px;
    line-height: 1.7;
    word-break: keep-all;
  }
  a {
    display: inline-flex;
    align-items: center;
    gap: 4px;
    padding: 7px 0;
    color: #e3d9ff;
    font-size: 15px;
    font-weight: 700;
    line-height: 20px;
    text-decoration: none;
  }
  a:hover {
    color: #fff;
  }
  a:focus-visible {
    outline: 3px solid ${theme.colors.stroke.focusRing};
    outline-offset: 4px;
    border-radius: 4px;
  }
  @media (max-width: 760px) {
    display: grid;
    justify-content: start;
    gap: 5px;
    margin-top: 20px;
  }
`;
