/** @jsxImportSource @emotion/react */
import { css } from '@emotion/react';
import type { JSX } from 'react';
import { theme } from '../../styles/theme';
import { LANDING_CONTACT_EMAIL, LANDING_TEAM } from './copy';

const LandingTeam = (): JSX.Element => (
  <section css={sectionStyle} aria-labelledby="landing-team-title">
    <div css={innerStyle}>
      <div css={introStyle} data-reveal="team-intro">
        <h2 id="landing-team-title">{LANDING_TEAM.title}</h2>
        <p>{LANDING_TEAM.description}</p>
        <div css={introLinksStyle}>
          <a
            href={LANDING_TEAM.repositoryUrl}
            target="_blank"
            rel="noopener noreferrer"
          >
            {LANDING_TEAM.repositoryLabel}
          </a>
          <a href={`mailto:${LANDING_CONTACT_EMAIL}`}>
            {LANDING_CONTACT_EMAIL}
          </a>
        </div>
      </div>
      <ul css={listStyle} aria-label="하루들 팀원">
        {LANDING_TEAM.members.map((member) => (
          <li key={member.githubId} css={cardStyle} data-reveal="team-member">
            <div css={avatarStyle}>
              <img
                src={member.imageUrl}
                alt=""
                loading="lazy"
                width={210}
                height={210}
              />
            </div>
            <strong>{member.nickname}</strong>
            <span css={nameStyle}>{member.name}</span>
            <a
              href={`https://github.com/${member.githubId}`}
              target="_blank"
              rel="noopener noreferrer"
              aria-label={`${member.nickname}(${member.name})의 GitHub`}
            >
              @{member.githubId}
            </a>
          </li>
        ))}
      </ul>
    </div>
  </section>
);

export default LandingTeam;

const sectionStyle = css`
  padding: clamp(88px, 10vw, 140px) clamp(24px, 5vw, 72px);
  background: #fff;
  @media (max-width: 767px) {
    padding: 80px 20px;
  }
`;
const innerStyle = css`
  display: grid;
  grid-template-columns: minmax(0, 0.78fr) minmax(0, 1.22fr);
  align-items: start;
  gap: clamp(40px, 7vw, 100px);
  width: min(100%, 1180px);
  margin: 0 auto;
  @media (max-width: 900px) {
    grid-template-columns: minmax(0, 1fr);
    gap: 32px;
  }
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
const introLinksStyle = css`
  display: grid;
  justify-items: start;
  gap: 4px;
  margin-top: 24px;
  a {
    padding: 6px 0;
    color: #6545cb;
    font-size: 15px;
    font-weight: 700;
    line-height: 20px;
    text-decoration: none;
    word-break: break-all;
  }
  a:hover {
    text-decoration: underline;
  }
  a:focus-visible {
    outline: 3px solid ${theme.colors.stroke.focusRing};
    outline-offset: 3px;
    border-radius: 4px;
  }
`;
const listStyle = css`
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 14px;
  margin: 0;
  padding: 0;
  list-style: none;
  @media (max-width: 560px) {
    grid-template-columns: repeat(2, minmax(0, 1fr));
    gap: 10px;
  }
`;
const cardStyle = css`
  display: grid;
  justify-items: center;
  min-width: 0;
  padding: 24px 12px 20px;
  border: 1px solid #eee9f7;
  border-radius: 24px;
  background: #fbfaff;
  text-align: center;
  strong {
    margin-top: 14px;
    color: #1c1530;
    font-size: 18px;
    font-weight: 800;
    line-height: 26px;
  }
  a {
    margin-top: 8px;
    padding: 4px 6px;
    max-width: 100%;
    overflow: hidden;
    color: #6545cb;
    font-size: 13px;
    font-weight: 700;
    line-height: 18px;
    text-decoration: none;
    text-overflow: ellipsis;
    white-space: nowrap;
  }
  a:hover {
    text-decoration: underline;
  }
  a:focus-visible {
    outline: 3px solid ${theme.colors.stroke.focusRing};
    outline-offset: 2px;
    border-radius: 4px;
  }
  @media (max-width: 560px) {
    padding: 20px 8px 16px;
    border-radius: 20px;
  }
`;
const avatarStyle = css`
  display: grid;
  place-items: center;
  width: 88px;
  height: 88px;
  overflow: hidden;
  border: 1px solid #e9e4f2;
  border-radius: 50%;
  background: #fff;
  img {
    width: 100%;
    height: 100%;
    object-fit: cover;
  }
  @media (max-width: 560px) {
    width: 72px;
    height: 72px;
  }
`;
const nameStyle = css`
  color: #81798b;
  font-size: 14px;
  line-height: 20px;
`;
