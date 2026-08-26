import { css } from '@emotion/react';
import { theme } from '../../styles/theme';
import checkIcon from '../../assets/icons/check.svg';
import { FINAL_STEP } from './useGenerateLoading';

const stepLabels = [
  '이야기 분석 중',
  '장면 구성 중',
  '스케치 그리는 중',
  '채색하고 마무리 중',
  '완료',
] as const;

const DiaryGenerateStepper = ({ loadingStep }: { loadingStep: number }) => {
  const progress =
    ((Math.min(loadingStep, FINAL_STEP) - 1) / (FINAL_STEP - 1)) * 100;

  return (
    <div css={stepperStyle}>
      <div css={progressRailStyle}>
        <div css={progressBarStyle(progress)} />
      </div>

      {stepLabels.map((label, index) => {
        const step = index + 1;
        const isComplete =
          step === FINAL_STEP ? step === loadingStep : step < loadingStep;
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
  padding: 14px 0 14px 38px;
`;

const progressRailStyle = css`
  position: absolute;
  top: 38px;
  left: 53px;
  width: 2px;
  height: 200px;
  overflow: hidden;
  border-radius: 1px;
  background-color: #e3e3e8;
`;

const progressBarStyle = (progress: number) => css`
  width: 100%;
  height: ${progress}%;
  background-color: ${theme.colors.bg.brand};
  transition: height 500ms ease-in-out;
`;

const stepStyle = css`
  position: relative;
  z-index: 1;
  display: flex;
  align-items: center;
  width: 262px;
  height: 48px;
  padding-left: 48px;
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
  border: 2px solid
    ${isComplete || isActive ? theme.colors.bg.brand : '#e3e3e8'};
  border-radius: 50%;
  background-color: ${isComplete ? theme.colors.bg.brand : '#ffffff'};
  box-sizing: border-box;
  transform: scale(${isActive ? 1.1 : 1});

  transition:
    border-color 400ms ease-in-out,
    background-color 400ms ease-in-out,
    transform 400ms ease-in-out;
`;

const labelStyle = css`
  color: ${theme.colors.text.primary};
  font-size: 16px;
  font-weight: 500;
  line-height: 24px;
  white-space: nowrap;
`;

const checkIconStyle = css`
  width: 24px;
  height: 24px;
`;
