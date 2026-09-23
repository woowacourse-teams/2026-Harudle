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
        placeholder="오늘은 민지와 함께 카페에 갔다. 처음으로 아이스 아메리카노를 마셨는데 너무 썼다. 다음에는 복숭아 아이스티를 마셔야겠다!"
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
    </div>
  );
};

export default DiaryInputField;

const fieldStyle = css`
  display: flex;
  flex-direction: column;
  gap: 8px;
  width: 100%;
  height: 100%;
  padding: 20px;
`;

const textAreaStyle = (hasError: boolean) => css`
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
  color: ${hasError ? theme.colors.foreground.critical : theme.colors.foreground.neutralMuted};
  font-size: 15px;
  font-weight: 400;
  line-height: 24px;
`;

const textAreaDescriptionStyle = css`
  display: flex;
  justify-content: space-between;
  align-items: center;
`;

const errorMessageStyle = css`
  display: flex;
  align-items: center;
  gap: 4px;
  height: 24px;
  color: ${theme.colors.foreground.critical};
  font-size: 15px;
  font-weight: 400;
  line-height: 24px;
`;

const warningIconStyle = css`
  width: 20px;
  height: 20px;
`;
