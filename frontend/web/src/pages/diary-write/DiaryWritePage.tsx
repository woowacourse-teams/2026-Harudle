import { useNavigate } from 'react-router';
import FloatingActionButton from '../../shared/FloatingActionButton';
import PageHeader from '../../shared/PageHeader';
import DiaryInputField from './DiaryInputField';
import { useState } from 'react';

const DiaryWritePage = () => {
  const navigate = useNavigate();
  const [diaryContent, setDiaryContent] = useState('');
  const [diaryContentError, setDiaryContentError] = useState<string | null>(
    null,
  );

  const handleDiarySubmit = (e: React.FormEvent<HTMLFormElement>) => {
    e.preventDefault();
    if (diaryContent.length < 10) {
      setDiaryContentError('10자 이상으로 입력해주세요!');
      return;
    }

    // 데이터만 보내고, 실제 요청은 생성 페이지에서 하도록 한다.
    navigate('/diary-generating', {
      state: {
        diaryDate: new Date().toLocaleDateString('sv-SE', {
          timeZone: 'Asia/Seoul',
        }),
        sourceText: diaryContent,
      },
    });
  };
  return (
    <div>
      <PageHeader
        left={<button>왼</button>}
        title={'새 일기 쓰기'}
        right={null}
      />

      <div>오늘의 하루를 자유롭게 적어주세요!</div>

      <form onSubmit={handleDiarySubmit}>
        <DiaryInputField
          diaryContent={diaryContent}
          onDiaryContentChange={(e: React.ChangeEvent<HTMLTextAreaElement>) => {
            setDiaryContent(e.target.value);
          }}
        />
        {diaryContentError ?? diaryContentError}

        <FloatingActionButton onClick={() => {}} disabled={false} />
      </form>
    </div>
  );
};

export default DiaryWritePage;
