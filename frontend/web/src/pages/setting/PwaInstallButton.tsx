import { css } from '@emotion/react';
import downloadIcon from '../../assets/icons/download.svg';
import { theme } from '../../styles/theme';
import { usePwaInstall } from './PwaInstallContext';

const INSTALL_GUIDE_URL =
  'https://harudle.notion.site/3ced0505d916802a83a1d5a5c9956295';

const PwaInstallButton = () => {
  const { status, install } = usePwaInstall();

  if (status === 'installed' || status === 'unavailable') {
    return null;
  }

  const handleClick = () => {
    if (status === 'ios-guide') {
      window.open(INSTALL_GUIDE_URL, '_blank', 'noopener,noreferrer');
      return;
    }

    void install();
  };

  return (
    <div css={installSectionStyle}>
      <button type="button" css={installButtonStyle} onClick={handleClick}>
        <span css={contentStyle}>
          <span css={titleStyle}>
            {status === 'ios-guide'
              ? '하루들을 홈 화면에 추가해 보세요'
              : '하루들을 앱으로 설치해 보세요'}
          </span>
          <span css={descriptionStyle}>
            홈 화면에서 더 빠르고 편하게 시작해요
          </span>
        </span>

        <span css={iconButtonStyle} aria-hidden="true">
          <span css={iconStyle} />
        </span>
      </button>

      <a
        css={guideLinkStyle}
        href={INSTALL_GUIDE_URL}
        target="_blank"
        rel="noreferrer"
      >
        설치가 처음이신가요? <strong>설치 방법 보기 →</strong>
      </a>
    </div>
  );
};

export default PwaInstallButton;

const installSectionStyle = css`
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 8px;
`;

const installButtonStyle = css`
  position: relative;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  width: 100%;
  min-height: 104px;
  padding: 16px 18px;
  overflow: hidden;
  border: none;
  border-radius: 20px;
  background: linear-gradient(
    135deg,
    ${theme.colors.bg.brand} 0%,
    #7355da 100%
  );
  box-shadow: 0 8px 20px rgb(115 85 218 / 20%);
  color: #ffffff;
  text-align: left;
  cursor: pointer;
  transition:
    transform 150ms ease,
    box-shadow 150ms ease;

  &::after {
    position: absolute;
    right: -34px;
    bottom: -56px;
    width: 132px;
    height: 132px;
    border-radius: 50%;
    background-color: rgb(255 255 255 / 8%);
    content: '';
  }

  &:active {
    transform: scale(0.98);
    box-shadow: 0 4px 12px rgb(115 85 218 / 18%);
  }

  &:focus-visible {
    outline: 3px solid rgb(115 85 218 / 24%);
    outline-offset: 3px;
  }

  @media (hover: hover) {
    &:hover {
      transform: translateY(-2px);
      box-shadow: 0 10px 24px rgb(115 85 218 / 26%);
    }
  }

  @media (prefers-reduced-motion: reduce) {
    transition: none;
  }
`;

const contentStyle = css`
  position: relative;
  z-index: 1;
  display: flex;
  flex: 1;
  flex-direction: column;
  align-items: flex-start;
  min-width: 0;
`;

const titleStyle = css`
  font-size: 16px;
  font-weight: 700;
  line-height: 24px;
`;

const descriptionStyle = css`
  color: rgb(255 255 255 / 78%);
  font-size: 12px;
  line-height: 18px;
`;

const iconButtonStyle = css`
  position: relative;
  z-index: 1;
  display: flex;
  flex: 0 0 auto;
  align-items: center;
  justify-content: center;
  width: 44px;
  height: 44px;
  border-radius: 50%;
  background-color: #ffffff;
  box-shadow: 0 4px 12px rgb(52 35 112 / 18%);
`;

const iconStyle = css`
  width: 24px;
  height: 24px;
  background-color: ${theme.colors.text.brand};
  -webkit-mask: url(${downloadIcon}) center / 24px 24px no-repeat;
  mask: url(${downloadIcon}) center / 24px 24px no-repeat;
`;

const guideLinkStyle = css`
  padding: 2px 4px;
  color: ${theme.colors.text.secondary};
  font-size: 12px;
  line-height: 18px;
  text-decoration: none;

  & > strong {
    color: ${theme.colors.text.brand};
    font-weight: 600;
  }

  &:focus-visible {
    border-radius: 4px;
    outline: 2px solid rgb(115 85 218 / 24%);
    outline-offset: 2px;
  }

  @media (hover: hover) {
    &:hover > strong {
      text-decoration: underline;
      text-underline-offset: 3px;
    }
  }
`;
