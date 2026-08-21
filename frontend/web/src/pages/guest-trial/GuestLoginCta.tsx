import { css } from '@emotion/react';
import kakaoIcon from '../../assets/icons/kakao.svg';
import { useAnalytics } from '../../shared/useAnalytics';
import { theme } from '../../styles/theme';

const KAKAO_OAUTH_PATH = '/oauth2/authorization/kakao';

type GuestLoginCtaProps = {
  label: string;
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

const GuestLoginCta = (props: GuestLoginCtaProps) => {
  const { track } = useAnalytics();

  const handleClick = () => {
    if (props.analyticsEvent === 'landing_direct_login_clicked') {
      track('landing_direct_login_clicked', { location: props.location });
      return;
    }

    track('landing_trial_login_clicked', { location: props.location });
  };

  return (
    <a href={KAKAO_OAUTH_PATH} css={linkStyle} onClick={handleClick}>
      <img src={kakaoIcon} alt="" css={iconStyle} />
      {props.label}
    </a>
  );
};

export default GuestLoginCta;

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
  background-color: #ffd66b;
  color: ${theme.colors.text.primary};
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
