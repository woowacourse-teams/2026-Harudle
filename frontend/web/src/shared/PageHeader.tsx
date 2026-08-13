import type { ReactNode } from 'react';
import { css } from '@emotion/react';
import { theme } from '../styles/theme';

const PageHeader = ({
  leftButton,
  title,
  rightButton,
}: {
  leftButton: ReactNode;
  title: string | null;
  rightButton: ReactNode | null;
}) => {
  return (
    <header css={pageHeaderStyle}>
      <div css={buttonSlotStyle}>{leftButton}</div>
      <h1 css={titleStyle}>{title}</h1>
      <div css={buttonSlotStyle}>{rightButton}</div>
    </header>
  );
};

export default PageHeader;

const pageHeaderStyle = css`
  display: flex;
  justify-content: space-between;
  align-items: center;
  width: 100%;
  height: 56px;
  padding: 0 8px;
  box-sizing: border-box;
`;

const buttonSlotStyle = css`
  display: flex;
  align-items: center;
  justify-content: center;
  width: 32px;
  height: 32px;
`;

const titleStyle = css`
  color: ${theme.colors.textPrimary};
  font-size: 22px;
  font-weight: 700;
  line-height: 32px;
  text-align: center;
`;
