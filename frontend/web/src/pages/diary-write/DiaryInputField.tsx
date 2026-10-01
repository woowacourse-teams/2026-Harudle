import { DIARY_WRITE_COPY } from './copy';
import { css } from '@emotion/react';
import warningIcon from '../../assets/icons/warning.svg';
import { theme } from '../../styles/theme';

const DiaryInputField = ({
  diaryContent,
  onDiaryContentChange,
  diaryContentError,
}: {
  diaryContent: string;
  onDiaryContentChange: (e: React.ChangeEvent<HTMLTextAreaElement>) => void;
  diaryContentError: string | null;
}) => {
  const MAX_LENGTH = 300;
  return (
    <div css={fieldStyle}>
      <textarea
        css={textAreaStyle(diaryContentError !== null)}
        placeholder={DIARY_WRITE_COPY.placeholder}
        value={diaryContent}
        maxLength={MAX_LENGTH}
        onChange={onDiaryContentChange}
      />

      <div css={textAreaDescriptionStyle}>
        <p css={errorMessageStyle}>
          {diaryContentError ? (
            <>
              <img src={warningIcon} alt="" css={warningIconStyle} />
              {diaryContentError}
            </>
          ) : null}
        </p>

        <span css={characterCountStyle(diaryContentError !== null)}>
          {diaryContent.length} / {MAX_LENGTH}
        </span>
      </div>

      <div css={characterTipStyle}>
        <span css={characterTipIconStyle}>✨</span>
        <p css={storyGuideStyle}>{DIARY_WRITE_COPY.characterTip}</p>
      </div>
    </div>
  );
};

export default DiaryInputField;

const fieldStyle = css`
  display: flex;
  flex-direction: column;
  gap: 8px;
  width: 100%;
  box-sizing: border-box;
`;

const textAreaStyle = (hasError: boolean) => css`
  word-break: keep-all;
  width: 100%;
  height: 100%;
  min-height: 210px;
  border: 1px solid
    ${hasError ? theme.colors.stroke.critical : theme.colors.stroke.outline};
  border-radius: 20px;
  padding: 20px;
  outline: none;
  resize: none;
  background-color: transparent;
  color: ${theme.colors.foreground.neutral};
  font-size: 15px;
  font-weight: 400;
  line-height: 28px;

  transition: all 0.2s ease-in-out;

  &::placeholder {
    color: ${theme.colors.foreground.neutralMuted};
    opacity: 1;
  }

  &:focus {
    border-color: ${hasError ? theme.colors.stroke.critical : theme.colors.stroke.brandSolid};
  }
`;

const characterCountStyle = (hasError: boolean) => css`
  flex-shrink: 0;
  color: ${hasError ? theme.colors.foreground.critical : theme.colors.foreground.neutralMuted};
  font-size: 15px;
  font-weight: 400;
  line-height: 24px;
`;

const textAreaDescriptionStyle = css`
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 8px;
`;

const errorMessageStyle = css`
  word-break: keep-all;
  display: flex;
  align-items: center;
  gap: 4px;
  min-height: 24px;
  color: ${theme.colors.foreground.critical};
  font-size: 15px;
  font-weight: 400;
  line-height: 24px;
`;

const warningIconStyle = css`
  flex-shrink: 0;
  width: 20px;
  height: 20px;
`;

const storyGuideStyle = css`
  margin: 0;
  color: ${theme.colors.foreground.neutralMuted};
  font-size: 13px;
  line-height: 22px;
  white-space: pre-line;
  word-break: keep-all;
`;

const characterTipStyle = css`
  display: flex;
  align-items: flex-start;
  gap: 8px;
  padding: 12px 14px;
  border-radius: 12px;
  background-color: ${theme.colors.background.brandWeak};
`;

const characterTipIconStyle = css`
  flex-shrink: 0;
  font-size: 16px;
  line-height: 22px;
`;
