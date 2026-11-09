import { css, keyframes } from '@emotion/react';
import { theme } from '../../styles/theme';

const FeedCategoryListSkeleton = () => {
  return (
    <div css={categoryListSkeletonStyle} aria-hidden="true">
      <span css={categoryItemSkeletonStyle} />
      <span css={categoryItemSkeletonStyle} />
      <span css={[categoryItemSkeletonStyle, wideCategoryItemSkeletonStyle]} />
    </div>
  );
};

export default FeedCategoryListSkeleton;

const shimmer = keyframes`
  to {
    background-position: -200% 0;
  }
`;

const categoryListSkeletonStyle = css`
  display: flex;
  align-items: center;
  gap: 16px;
  height: 48px;
  overflow: hidden;
`;

const categoryItemSkeletonStyle = css`
  flex: 0 0 28px;
  height: 20px;
  border-radius: 4px;
  background: linear-gradient(
    90deg,
    ${theme.colors.background.neutralWeak} 25%,
    ${theme.colors.background.brandWeak} 50%,
    ${theme.colors.background.neutralWeak} 75%
  );
  background-size: 200% 100%;
  animation: ${shimmer} 1.5s linear infinite;

  @media (prefers-reduced-motion: reduce) {
    animation: none;
  }
`;

const wideCategoryItemSkeletonStyle = css`
  flex-basis: 42px;
`;
