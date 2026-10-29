import { css } from '@emotion/react';
import type { ReactNode } from 'react';

interface VisuallyHiddenProps {
  readonly children: ReactNode;
}

const VisuallyHidden = ({ children }: VisuallyHiddenProps) => {
  return <div css={visuallyHiddenStyle}>{children}</div>;
};

export default VisuallyHidden;

const visuallyHiddenStyle = css`
  position: absolute;
  width: 1px;
  height: 1px;
  margin: -1px;
  overflow: hidden;
  clip-path: inset(50%);
  white-space: nowrap;
`;
