import { css } from '@emotion/react';
import { theme } from '../styles/theme';
import plusIcon from '../assets/icons/plus.svg';
import arrowRightIcon from '../assets/icons/arrow-right.svg';

type FloatingActionButtonIcon = 'plus' | 'arrow-right';

const FloatingActionButton = ({
  onClick,
  disabled,
  icon = 'plus',
  type = 'button',
}: {
  onClick: () => void;
  disabled: boolean;
  icon?: FloatingActionButtonIcon;
  type?: 'button' | 'submit';
}) => {
  const iconSrc = icon === 'arrow-right' ? arrowRightIcon : plusIcon;

  return (
    <button
      type={type}
      css={buttonStyle(disabled)}
      onClick={onClick}
      disabled={disabled}
    >
      <img src={iconSrc} alt="" css={iconStyle} />
    </button>
  );
};

export default FloatingActionButton;

const buttonStyle = (disabled: boolean) => css`
  position: absolute;
  right: 20px;
  bottom: 96px;
  z-index: 10;

  display: flex;
  align-items: center;
  justify-content: center;

  width: 52px;
  height: 52px;
  padding: 0;
  border: none;
  border-radius: 50%;

  background-color: ${theme.colors.primary};
  box-shadow: 0 8px 24px rgba(51, 36, 89, 0.16);
  opacity: ${disabled ? 0.4 : 1};
  cursor: ${disabled ? 'default' : 'pointer'};

  &:active {
    transform: ${disabled ? 'none' : 'scale(0.96)'};
  }
`;

const iconStyle = css`
  display: block;
  width: 24px;
  height: 24px;
`;
