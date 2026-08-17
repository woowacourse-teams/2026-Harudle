import { css } from '@emotion/react';
import type { ReactNode } from 'react';
import { theme } from '../styles/theme';

const PageHeader = ({
  left,
  title,
  right,
}: {
  left: ReactNode | null;
  title: string | null;
  right: ReactNode | null;
}) => {
  return (
    <header css={pageHeaderStyle}>
      <div>{left}</div>
      <h1 css={titleStyle}>{title}</h1>
      <div>{right}</div>
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

const titleStyle = css`
  color: ${theme.colors.text.primary};
  font-size: 22px;
  font-weight: 700;
  line-height: 32px;
  text-align: center;
`;
