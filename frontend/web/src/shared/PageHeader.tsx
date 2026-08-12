import type { ReactNode } from 'react';

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
    <div>
      {leftButton} {title} {rightButton}
    </div>
  );
};

export default PageHeader;
