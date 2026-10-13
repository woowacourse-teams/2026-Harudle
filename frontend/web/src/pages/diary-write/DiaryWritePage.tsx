import { DIARY_WRITE_COPY } from './copy';
import { Navigate, useNavigate } from 'react-router';
import PageHeader from '../../shared/PageHeader';
import ActionButton from '../../shared/ActionButton';
import DiaryInputField from './DiaryInputField';
import { useState } from 'react';
import backIcon from '../../assets/icons/back.svg';
import { css } from '@emotion/react';
import { theme } from '../../styles/theme';
import { useDiaryGenerateContext } from '../diary-generating/DiaryGenerateContext';
import { getToday } from '../../shared/utils';
import { DIARY_CONTENT_SESSION_KEY } from '../../shared/constants';

const DiaryWritePage = () => {
  const navigate = useNavigate();
  const [diaryContent, setDiaryContent] = useState(
    sessionStorage.getItem(DIARY_CONTENT_SESSION_KEY) ?? '',
  );
  const [diaryContentError, setDiaryContentError] = useState<string | null>(
    null,
  );
  const { request } = useDiaryGenerateContext();

  if (request.status === 'loading') {
    alert(DIARY_WRITE_COPY.generationInProgress);
    return <Navigate to="/album" replace />;
  }

  const handleDiarySubmit = (e: React.FormEvent<HTMLFormElement>) => {
    e.preventDefault();
    if (diaryContent.length < 10) {
      setDiaryContentError(DIARY_WRITE_COPY.minLengthError);
      return;
    }

    sessionStorage.setItem(DIARY_CONTENT_SESSION_KEY, diaryContent);

    const { year, month, day } = getToday();
    navigate('/diary-generating', {
      state: {
        diaryDate: `${year}-${String(month).padStart(2, '0')}-${String(day).padStart(2, '0')}`,
        sourceText: diaryContent,
        idempotencyKey: crypto.randomUUID(),
      },
      replace: true,
    });
  };

  return (
    <div css={pageStyle}>
      <PageHeader
        left={
          <button
            type="button"
            aria-label="뒤로 가기"
            css={headerButtonStyle}
            onClick={() => navigate('/album')}
          >
            <img
              src={backIcon}
              alt="뒤로가기 아이콘"
              css={headerButtonIconStyle}
            />
          </button>
        }
        title={null}
        right={null}
      />

      <main css={contentStyle}>
        <div css={promptStyle}>
          <h2 css={promptTitleStyle}>
            {DIARY_WRITE_COPY.questionLines[0]}
            <br />
            {DIARY_WRITE_COPY.questionLines[1]}
          </h2>
          <p css={promptDescriptionStyle}>{DIARY_WRITE_COPY.guide}</p>
        </div>

        <form css={formStyle} onSubmit={handleDiarySubmit}>
          <DiaryInputField
            diaryContent={diaryContent}
            onDiaryContentChange={(e) => {
              setDiaryContentError(null);
              setDiaryContent(e.target.value);
            }}
            diaryContentError={diaryContentError}
          />

          <div css={submitButtonContainerStyle}>
            <ActionButton type="submit" label={DIARY_WRITE_COPY.createAction} />
          </div>
        </form>
      </main>
    </div>
  );
};

export default DiaryWritePage;

const pageStyle = css`
  position: relative;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 14px;
  width: 100%;
  height: 100%;
  padding: 20px;
  overflow-y: auto;
  background-color: ${theme.colors.background.surface};
`;

const headerButtonStyle = css`
  display: flex;
  align-items: center;
  justify-content: center;
  width: 32px;
  height: 32px;
  padding: 0;
  border: none;
  background-color: transparent;
  cursor: pointer;
`;

const headerButtonIconStyle = css`
  width: 24px;
  height: 24px;
`;

const contentStyle = css`
  display: flex;
  flex-direction: column;
  gap: 32px;
  width: 100%;
  height: 100%;
`;

const promptStyle = css`
  display: flex;
  flex-direction: column;
  gap: 12px;
  padding: 0 20px;
  text-align: center;
`;

const promptTitleStyle = css`
  margin: 0;
  word-break: keep-all;
  color: ${theme.colors.foreground.neutral};
  font-size: 22px;
  font-weight: 700;
  line-height: 34px;
  text-align: center;
`;

const promptDescriptionStyle = css`
  margin: 0;
  color: ${theme.colors.foreground.neutralMuted};
  font-size: 14px;
  line-height: 22px;
  white-space: pre-line;
  word-break: keep-all;
`;

const formStyle = css`
  width: 100%;
  padding: 0 20px 20px;
`;

const submitButtonContainerStyle = css`
  margin-top: 24px;
`;
