import { css } from '@emotion/react';
import kakaoIcon from '../../assets/icons/kakao.svg';
import { theme } from '../../styles/theme';

export const KAKAO_OAUTH_PATH = '/oauth2/authorization/kakao';

const GuestLoginCta = ({ label }: { label: string }) => {
  return (
    <a href={KAKAO_OAUTH_PATH} css={linkStyle}>
      <img src={kakaoIcon} alt="" css={iconStyle} />
      {label}
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
