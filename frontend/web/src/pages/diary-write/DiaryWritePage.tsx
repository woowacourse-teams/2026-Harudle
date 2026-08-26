import { Navigate, useNavigate } from 'react-router';
import FloatingActionButton from '../../shared/FloatingActionButton';
import PageHeader from '../../shared/PageHeader';
import DiaryInputField from './DiaryInputField';
import { useState } from 'react';
import backIcon from '../../assets/icons/back.svg';
import { css } from '@emotion/react';
import { theme } from '../../styles/theme';
import nextIcon from '../../assets/icons/arrow-right.svg';
import { useDiaryGenerateContext } from '../diary-generating/DiaryGenerateContext';
import { getToday } from '../../shared/utils';

const DiaryWritePage = () => {
  const navigate = useNavigate();
  const [diaryContent, setDiaryContent] = useState(
    sessionStorage.getItem('diaryContent') ?? '',
  );
  const [diaryContentError, setDiaryContentError] = useState<string | null>(
    null,
  );
  const { diaryGenerateRequest } = useDiaryGenerateContext();

  if (diaryGenerateRequest.status === 'loading') {
    alert('다른 일기가 생성중입니다.');
    return <Navigate to="/" replace />;
  }

  const handleDiarySubmit = (e: React.FormEvent<HTMLFormElement>) => {
    e.preventDefault();
    if (diaryContent.length < 10) {
      setDiaryContentError('10자 이상으로 입력해주세요!');
      return;
    }

    sessionStorage.setItem('diaryContent', diaryContent);

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
            onClick={() => navigate(-1)}
          >
            <img
              src={backIcon}
              alt="뒤로가기 아이콘"
              css={headerButtonIconStyle}
            />
          </button>
        }
        title={'새 일기 쓰기'}
        right={null}
      />

      <main css={contentStyle}>
        <h2 css={promptTitleStyle}>
          오늘의 하루를
          <br />
          자유롭게 적어주세요!
        </h2>

        <form css={formStyle} onSubmit={handleDiarySubmit}>
          <DiaryInputField
            diaryContent={diaryContent}
            onDiaryContentChange={(e) => {
              setDiaryContentError(null);
              setDiaryContent(e.target.value);
            }}
            diaryContentError={diaryContentError}
          />

          <FloatingActionButton
            onClick={() => {}}
            icon={<img css={nextIconStyle} src={nextIcon} />}
            disabled={false}
          />
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
  overflow: hidden;
  background-color: #ffffff;
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
  gap: 12px;
  width: 100%;
  height: 100%;
`;

const promptTitleStyle = css`
  color: ${theme.colors.text.primary};
  font-size: 22px;
  font-weight: 700;
  line-height: 34px;
  text-align: center;
`;

const formStyle = css`
  width: 100%;
`;

const nextIconStyle = css`
  width: 24px;
  height: 24px;
`;
