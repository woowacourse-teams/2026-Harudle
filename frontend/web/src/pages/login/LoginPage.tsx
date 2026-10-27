import { css, type SerializedStyles } from '@emotion/react';
import harudleLogo from '../../assets/images/harudle-logo.webp';
import loginHero from '../../assets/images/login-shared-comic.png';
import kakaoIcon from '../../assets/icons/kakao-login.svg';
import googleIcon from '../../assets/icons/google.png';
import appleIcon from '../../assets/icons/apple.svg';
import { useState, type ReactElement } from 'react';
import { theme } from '../../styles/theme';
import { Link, useNavigate } from 'react-router';
import { isMswEnabled } from '../../shared/environment';
import { LOGIN_COPY } from './copy';
import {
  getLastSuccessfulLoginProvider,
  setLoginAttemptProvider,
  type LoginProvider,
} from './loginHistory';

const providers = [
  { id: 'kakao', icon: kakaoIcon, label: '카카오로 계속하기' },
  { id: 'google', icon: googleIcon, label: 'Google로 계속하기' },
  { id: 'apple', icon: appleIcon, label: 'Apple로 계속하기' },
] as const;

const LoginPage = () => {
  const navigate = useNavigate();
  const [lastProvider] = useState(getLastSuccessfulLoginProvider);

  const handleLogin = (provider: LoginProvider): void => {
    setLoginAttemptProvider(provider);
    if (isMswEnabled) {
      navigate('/auth/callback');
      return;
    }

    window.location.assign(`/oauth2/authorization/${provider}`);
  };

  return (
    <div css={pageStyle}>
      <div css={topSpaceStyle} aria-hidden="true" />
      <div css={logoFrameStyle}>
        <img src={harudleLogo} alt="하루들 로고" css={logoStyle} />
      </div>
      <div css={brandSpaceStyle} aria-hidden="true" />
      <div css={heroImageFrameStyle}>
        <img
          src={loginHero}
          alt="네컷만화를 함께 들고 웃는 하루들 캐릭터와 강아지"
          css={heroImageStyle}
        />
      </div>

      <div css={storySpaceStyle} aria-hidden="true" />
      <h1 css={titleStyle}>
        {LOGIN_COPY.taglineLines[0]}
        <br />
        <span css={accentStyle}>{LOGIN_COPY.taglineLines[1]}</span>
      </h1>

      <div css={actionSpaceStyle} aria-hidden="true" />
      <div css={loginAreaStyle}>
        <div css={buttonsStyle}>
          {providers.map(({ id, icon, label }): ReactElement => (
            <div key={id} css={buttonWrapperStyle}>
              <button
                type="button"
                css={loginButtonStyle(id, id === lastProvider)}
                onClick={(): void => handleLogin(id)}
                aria-describedby={
                  id === lastProvider ? 'recent-login' : undefined
                }
              >
                <img src={icon} alt="" css={providerIconStyle(id)} />
                {label}
              </button>
              {id === lastProvider && (
                <span id="recent-login" css={recentLoginStyle}>
                  <svg
                    width="14"
                    height="14"
                    viewBox="0 0 24 24"
                    fill="none"
                    aria-hidden="true"
                  >
                    <circle
                      cx="12"
                      cy="12"
                      r="9"
                      stroke="currentColor"
                      strokeWidth="1.8"
                    />
                    <path
                      d="M12 6v6l4 2"
                      stroke="currentColor"
                      strokeWidth="1.8"
                      strokeLinecap="round"
                      strokeLinejoin="round"
                    />
                  </svg>
                  최근 로그인
                </span>
              )}
            </div>
          ))}
        </div>

        <p css={noticeStyle}>
          <Link to="/legal/terms">{LOGIN_COPY.terms}</Link>
          <span aria-hidden="true"> · </span>
          <Link to="/legal/privacy">{LOGIN_COPY.privacy}</Link>
        </p>
      </div>
    </div>
  );
};

export default LoginPage;

const pageStyle = css`
  @font-face {
    font-family: 'Google Sans';
    font-style: normal;
    font-weight: 500;
    font-display: swap;
    src: url(${new URL('../../assets/fonts/GoogleSans-Medium.ttf', import.meta.url).href})
      format('truetype');
  }

  display: flex;
  flex-direction: column;
  align-items: center;
  width: 100%;
  height: 100%;
  padding: 16px 20px 20px;
  overflow-y: auto;
  background-color: ${theme.colors.background.surface};
`;

const topSpaceStyle = css`
  flex: 0.8 0 12px;
`;

const brandSpaceStyle = css`
  flex: 1 0 24px;
`;

const storySpaceStyle = css`
  flex: 0.5 0 16px;
`;

const actionSpaceStyle = css`
  flex: 0.65 0 24px;
`;

// 이미지 안의 투명 여백을 제외해 보이는 콘텐츠 사이로 간격을 맞춘다.
const logoFrameStyle = css`
  flex-shrink: 0;
  width: min(246px, 100%, 30dvh);
  aspect-ratio: 1536 / 560;
  overflow: hidden;
`;

const logoStyle = css`
  display: block;
  width: 100%;
  height: 100%;
  object-fit: cover;
`;

const heroImageFrameStyle = css`
  flex-shrink: 0;
  width: min(336px, 100%, 46dvh);
  aspect-ratio: 1536 / 768;
  overflow: hidden;
`;

const heroImageStyle = css`
  display: block;
  width: 100%;
  height: 100%;
  object-fit: cover;
  object-position: center 62.5%;
`;

const titleStyle = css`
  flex-shrink: 0;
  width: 100%;
  color: ${theme.colors.foreground.neutral};
  font-size: 26px;
  font-weight: 700;
  line-height: 1.3;
  letter-spacing: -0.02em;
  text-align: center;
  word-break: keep-all;
`;

const accentStyle = css`
  color: ${theme.colors.foreground.brand};
  font-size: 30px;
`;

const loginAreaStyle = css`
  display: flex;
  flex-shrink: 0;
  flex-direction: column;
  align-items: center;
  gap: 10px;
  width: 100%;
`;

const buttonsStyle = css`
  display: flex;
  flex-direction: column;
  gap: 12px;
  width: 100%;
  max-width: 342px;
  padding-top: 34px;
`;

const buttonWrapperStyle = css`
  position: relative;
`;

// 공급자 기본 색상은 유지하고 최근 로그인 방법만 보라색 테두리로 표시한다.
const loginButtonStyle = (
  provider: LoginProvider,
  isRecentLogin: boolean,
): SerializedStyles => css`
  position: relative;
  width: 100%;
  height: 48px;
  padding: 0 48px;
  overflow: hidden;
  border: 1px solid
    ${isRecentLogin ? theme.colors.stroke.brandStrong : provider === 'google' ? '#747775' : 'transparent'};
  border-radius: 12px;
  box-shadow: ${isRecentLogin ? `0 0 0 1px ${theme.colors.stroke.brandStrong}` : 'none'};
  background-color: ${provider === 'kakao' ? '#FEE500' : provider === 'apple' ? '#000000' : '#FFFFFF'};
  color: ${provider === 'apple' ? '#FFFFFF' : provider === 'google' ? '#1F1F1F' : 'rgb(0 0 0 / 85%)'};
  font-family:
    ${provider === 'google' ? "'Google Sans', " : ''}-apple-system,
    BlinkMacSystemFont, system-ui, sans-serif;
  font-size: 16px;
  font-weight: 500;
  line-height: 1.4;
  cursor: pointer;

  &:focus-visible {
    outline: 3px solid ${theme.colors.stroke.focusRing};
    outline-offset: 3px;
  }
`;

const providerIconStyle = (provider: LoginProvider): SerializedStyles => css`
  position: absolute;
  top: 50%;
  left: ${provider === 'apple' ? '9px' : '14px'};
  width: ${provider === 'apple' ? 'auto' : '24px'};
  height: ${provider === 'apple' ? '48px' : '24px'};
  transform: translateY(-50%);
`;

const recentLoginStyle = css`
  position: absolute;
  z-index: 1;
  right: -12px;
  bottom: calc(100% + 10px);
  display: inline-flex;
  align-items: center;
  gap: 5px;
  padding: 4px 10px;
  border-radius: 8px;
  background-color: ${theme.colors.background.brandStrong};
  filter: drop-shadow(0 2px 4px rgb(57 33 106 / 14%));
  color: ${theme.colors.foreground.onBrand};
  font-size: 12px;
  font-weight: 600;
  line-height: 18px;
  white-space: nowrap;
  pointer-events: none;

  &::after {
    position: absolute;
    right: 16px;
    bottom: -5px;
    width: 10px;
    height: 6px;
    background-color: inherit;
    clip-path: polygon(0 0, 100% 0, 50% 100%);
    content: '';
  }

  @media (max-width: 360px) {
    bottom: calc(100% + 7px);
    gap: 4px;
    padding: 0 8px;
    font-size: 11px;
    line-height: 16px;

    &::after {
      bottom: -3px;
      width: 8px;
      height: 4px;
    }
  }
`;

const noticeStyle = css`
  width: 100%;
  max-width: 342px;
  color: ${theme.colors.foreground.neutralMuted};
  font-size: 12px;
  font-weight: 400;
  line-height: 18px;
  text-align: center;
`;
