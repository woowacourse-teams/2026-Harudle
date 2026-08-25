import { css, keyframes } from '@emotion/react';
import { theme } from '../../styles/theme';
import { formatDiaryDate, getToday } from '../../shared/utils';

const DiaryItemRowSkeleton = () => {
  const { year, month, day } = getToday();
  const date = `${year}-${month}-${day}`;
  const { date: formattedDate, weekday } = formatDiaryDate(date);

  return (
    <div css={skeletonRowStyle}>
      <div css={dateStyle}>
        <strong>{formattedDate}</strong>
        <span>{weekday}</span>
      </div>

      <div css={titleSkeletonStyle} />
      <div css={thumbnailSkeletonStyle} />
    </div>
  );
};

export default DiaryItemRowSkeleton;

const shimmer = keyframes`
  to {
    background-position: -200% 0;
  }
`;

const skeletonStyle = css`
  background: linear-gradient(90deg, #f3effa 25%, #e7e0f3 50%, #f3effa 75%);
  background-size: 200% 100%;
  animation: ${shimmer} 1.5s linear infinite;
`;

const skeletonRowStyle = css`
  position: relative;
  display: flex;
  gap: 12px;
  justify-content: space-between;
  align-items: center;
  width: 100%;
  height: 82px;
  padding: 0 6px 0 45px;

  &::before {
    content: '';
    position: absolute;
    top: 0;
    bottom: -12px;
    left: 25px;
    width: 2px;
    background: #ded8ff;
  }

  &::after {
    content: '';
    position: absolute;
    top: 32px;
    left: 26px;
    width: 12px;
    height: 12px;
    border-radius: 50%;
    background: #aaa8b2;
    transform: translate(-50%, -50%);
  }
`;

const dateStyle = css`
  display: flex;
  flex-direction: column;
  gap: 2px;
  justify-content: center;
  min-width: 58px;
  height: 64px;
  white-space: nowrap;

  strong {
    color: ${theme.colors.text.primary};
    font-size: 15px;
    font-weight: 700;
    line-height: 24px;
  }

  span {
    color: ${theme.colors.text.secondary};
    font-size: 12px;
    line-height: 18px;
  }
`;

const titleSkeletonStyle = css`
  ${skeletonStyle};
  flex: 1;
  height: 18px;
  border-radius: 999px;
`;

const thumbnailSkeletonStyle = css`
  ${skeletonStyle};
  flex: 0 0 130px;
  width: 130px;
  height: 80px;
  border-radius: 12px;
`;
