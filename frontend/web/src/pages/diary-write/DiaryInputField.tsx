import { css } from '@emotion/react';
import warningIcon from '../../assets/icons/warning.svg';
import { theme } from '../../styles/theme';

const MAX_DIARY_LENGTH = 300;

const DiaryInputField = ({
  diaryContent,
  onDiaryContentChange,
  error,
}: {
  diaryContent: string;
  onDiaryContentChange: (e: React.ChangeEvent<HTMLTextAreaElement>) => void;
  error: string | null;
}) => {
  return (
    <div css={inputFieldStyle}>
      <div css={textAreaContainerStyle(Boolean(error))}>
        <textarea
          css={textAreaStyle}
          placeholder="오늘은 민지와 함께 카페에 갔다. 처음으로 아이스 아메리카노를 마셨는데 너무 썼다. 다음에는 복숭아 아이스티를 마셔야겠다!"
          value={diaryContent}
          maxLength={MAX_DIARY_LENGTH}
          onChange={onDiaryContentChange}
        />
        <span css={characterCountStyle(Boolean(error))}>
          {diaryContent.length} / {MAX_DIARY_LENGTH}
        </span>
      </div>

      {error && (
        <p id="diary-content-error" css={errorMessageStyle}>
          <img src={warningIcon} alt="" css={warningIconStyle} />
          {error}
        </p>
      )}
    </div>
  );
};

export default DiaryInputField;

const inputFieldStyle = css`
  display: flex;
  flex-direction: column;
  gap: 8px;
  width: 342px;
`;

const textAreaContainerStyle = (hasError: boolean) => css`
  position: relative;
  width: 100%;
  height: 210px;
  overflow: hidden;
  border: 1px solid ${hasError ? theme.colors.danger : theme.colors.border};
  border-radius: 20px;
  background-color: ${theme.colors.background};
  box-sizing: border-box;

  &:focus-within {
    border-color: ${hasError ? theme.colors.danger : theme.colors.accent};
  }
`;

const textAreaStyle = css`
  width: 100%;
  height: 100%;
  padding: 18px 19px 48px;
  border: none;
  outline: none;
  resize: none;
  background-color: transparent;
  color: ${theme.colors.textPrimary};
  font-size: 15px;
  font-weight: 400;
  line-height: 28px;
  box-sizing: border-box;

  &::placeholder {
    color: ${theme.colors.textSecondary};
    opacity: 1;
  }
`;

const characterCountStyle = (hasError: boolean) => css`
  position: absolute;
  right: 19px;
  bottom: 15px;
  color: ${hasError ? theme.colors.danger : theme.colors.textSecondary};
  font-size: 15px;
  font-weight: 400;
  line-height: 24px;
`;

const errorMessageStyle = css`
  display: flex;
  align-items: center;
  gap: 4px;
  height: 24px;
  color: ${theme.colors.danger};
  font-size: 15px;
  font-weight: 400;
  line-height: 24px;
`;

const warningIconStyle = css`
  width: 20px;
  height: 20px;
`;
