import { css } from '@emotion/react';
import type { JSX } from 'react';
import kakaoIcon from '../../assets/icons/kakao.svg';
import { theme } from '../../styles/theme';
import { useAnalytics } from '../../posthog/useAnalytics';

const KAKAO_OAUTH_PATH = '/oauth2/authorization/kakao';

type LandingLoginCtaProps = {
  label: string;
  appearance?: 'button' | 'text';
} & (
  | {
      analyticsEvent: 'landing_direct_login_clicked';
      location: 'hero' | 'final';
    }
  | {
      analyticsEvent: 'landing_trial_login_clicked';
      location: 'result' | 'already_used';
    }
);

const LandingLoginCta = (props: LandingLoginCtaProps): JSX.Element => {
  const { track } = useAnalytics();

  const handleClick = (): void => {
    if (props.analyticsEvent === 'landing_direct_login_clicked') {
      track('landing_direct_login_clicked', { location: props.location });
      return;
    }

    track('landing_trial_login_clicked', { location: props.location });
  };

  return (
    <a
      href={KAKAO_OAUTH_PATH}
      css={
        props.appearance === 'text'
          ? [textLinkStyle, props.location === 'final' && finalTextLinkStyle]
          : linkStyle
      }
      onClick={handleClick}
    >
      {props.appearance !== 'text' && (
        <img src={kakaoIcon} alt="" css={iconStyle} />
      )}
      {props.label}
    </a>
  );
};

export default LandingLoginCta;

const linkStyle = css`
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 10px;
  width: 100%;
  max-width: 342px;
  min-height: 56px;
  padding: 14px 20px;
  border-radius: 16px;
  background-color: ${theme.colors.background.kakao};
  color: ${theme.colors.foreground.neutral};
  font-size: 16px;
  font-weight: 700;
  line-height: 24px;
  text-decoration: none;

  &:active {
    transform: scale(0.98);
  }
`;

const iconStyle = css`
  width: 24px;
  height: 24px;
`;

const textLinkStyle = css`
  padding: 12px 0;
  color: ${theme.colors.foreground.neutralMuted};
  font-size: 12px;
  line-height: 22px;
  text-decoration: none;

  &:focus-visible {
    outline: 3px solid ${theme.colors.stroke.focusRing};
    outline-offset: 4px;
  }
`;

const finalTextLinkStyle = css`
  padding: 8px 0;
  color: ${theme.colors.foreground.brand};
  font-size: 13px;
  font-weight: 600;
  text-decoration: underline;
  text-underline-offset: 4px;
`;
