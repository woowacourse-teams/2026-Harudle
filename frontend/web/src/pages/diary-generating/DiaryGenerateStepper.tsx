import { css } from '@emotion/react';
import { theme } from '../../styles/theme';
import checkIcon from '../../assets/icons/check.svg';

const stepLabels = [
  '이야기 분석 중',
  '장면 구성 중',
  '스케치 그리는 중',
  '채색하고 마무리 중',
] as const;

const DiaryGenerateStepper = ({ loadingStep }: { loadingStep: number }) => {
  const progress = ((Math.min(loadingStep, 4) - 1) / 3) * 100;

  return (
    <div css={stepperStyle}>
      <div css={progressRailStyle(progress)} />

      {stepLabels.map((label, index) => {
        const step = index + 1;
        const isComplete = step < loadingStep;
        const isActive = step === loadingStep;

        return (
          <div css={stepStyle} key={label}>
            <div
              css={indicatorStyle({
                isComplete,
                isActive,
              })}
            >
              {isComplete && (
                <img css={checkIconStyle} src={checkIcon} alt="" />
              )}
            </div>
            <span css={labelStyle}>{label}</span>
          </div>
        );
      })}
    </div>
  );
};

export default DiaryGenerateStepper;

const stepperStyle = css`
  position: relative;
  width: 300px;
  height: 220px;
  padding: 14px 0 14px 38px;
  overflow: hidden;
  box-sizing: border-box;
`;

const progressRailStyle = (progress: number) => css`
  position: absolute;
  top: 38px;
  left: 53px;
  width: 2px;
  height: 144px;
  border-radius: 1px;
  background: linear-gradient(
    to bottom,
    ${theme.colors.primary} ${progress}%,
    #e3e3e8 ${progress}%
  );
`;

const stepStyle = css`
  position: relative;
  z-index: 1;
  display: flex;
  align-items: center;
  width: 262px;
  height: 48px;
  padding-left: 48px;
  box-sizing: border-box;
`;

const indicatorStyle = ({
  isComplete,
  isActive,
}: {
  isComplete: boolean;
  isActive: boolean;
}) => css`
  position: absolute;
  top: 12px;
  left: 4px;
  display: flex;
  align-items: center;
  justify-content: center;
  width: 24px;
  height: 24px;
  border: ${isComplete ? 'none' : `2px solid ${isActive ? theme.colors.primary : '#e3e3e8'}`};
  border-radius: 50%;
  color: ${theme.colors.background};
  font-size: 16px;
  line-height: 24px;
  background-color: ${
    isComplete ? theme.colors.primary : theme.colors.background
  };
  box-sizing: border-box;
`;

const labelStyle = css`
  color: ${theme.colors.textPrimary};
  font-family: 'Noto Sans KR', sans-serif;
  font-size: 16px;
  font-weight: 500;
  line-height: 24px;
  white-space: nowrap;
`;

const checkIconStyle = css`
  width: 24px;
  height: 24px;
`;
