import { useNavigate } from 'react-router';
import FloatingActionButton from '../../shared/FloatingActionButton';
import PageHeader from '../../shared/PageHeader';
import DiaryInputField from './DiaryInputField';
import { useState } from 'react';
import { css } from '@emotion/react';
import backIcon from '../../assets/icons/back.svg';
import { theme } from '../../styles/theme';
import { formatKoreanDate } from '../../shared/date';

const DiaryWritePage = () => {
  const navigate = useNavigate();
  const [diaryContent, setDiaryContent] = useState('');
  const [diaryContentError, setDiaryContentError] = useState<string | null>(
    null,
  );

  const handleDiarySubmit = (e: React.FormEvent<HTMLFormElement>) => {
    e.preventDefault();
    if (diaryContent.length < 10) {
      setDiaryContentError('최소 10자 이상 입력해주세요.');
      return;
    }

    // 데이터만 보내고, 실제 요청은 생성 페이지에서 하도록 한다.
    navigate('/diary-generating', {
      state: {
        diaryDate: formatKoreanDate(new Date()),
        sourceText: diaryContent,
      },
    });
  };

  const handleDiaryContentChange = (
    e: React.ChangeEvent<HTMLTextAreaElement>,
  ) => {
    setDiaryContent(e.target.value);
    setDiaryContentError(null);
  };

  return (
    <div css={diaryWritePageStyle}>
      <PageHeader
        leftButton={
          <button
            type="button"
            aria-label="뒤로 가기"
            css={backButtonStyle}
            onClick={() => navigate(-1)}
          >
            <img src={backIcon} alt="" css={backIconStyle} />
          </button>
        }
        title="새 일기 쓰기"
        rightButton={null}
      />

      <main css={contentStyle}>
        <h2 css={promptTitleStyle}>
          오늘의 하루를
          <br />
          자유롭게 적어주세요!
        </h2>

        <form onSubmit={handleDiarySubmit}>
          <DiaryInputField
            diaryContent={diaryContent}
            error={diaryContentError}
            onDiaryContentChange={handleDiaryContentChange}
          />

          <FloatingActionButton
            type="submit"
            icon="arrow-right"
            onClick={() => {}}
            disabled={diaryContent.length === 0}
          />
        </form>
      </main>
    </div>
  );
};

export default DiaryWritePage;

const diaryWritePageStyle = css`
  position: relative;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 14px;
  width: 100%;
  height: 100%;
  padding: 12px 20px 10px;
  overflow: hidden;
  background-color: ${theme.colors.background};
  box-sizing: border-box;
`;

const backButtonStyle = css`
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

const backIconStyle = css`
  width: 24px;
  height: 24px;
`;

const contentStyle = css`
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 14px;
  width: 100%;
`;

const promptTitleStyle = css`
  color: ${theme.colors.textPrimary};
  font-family: 'Noto Sans KR', sans-serif;
  font-size: 22px;
  font-weight: 700;
  line-height: 34px;
  text-align: center;
`;
