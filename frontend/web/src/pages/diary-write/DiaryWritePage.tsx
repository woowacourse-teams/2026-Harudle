import { useNavigate } from 'react-router';
import FloatingActionButton from '../../shared/FloatingActionButton';
import PageHeader from '../../shared/PageHeader';
import DiaryInputField from './DiaryInputField';
import { useState } from 'react';
import { API_BASE_URL, type ApiRequestStatus } from '../../shared/api';

const DiaryWritePage = () => {
  const navigate = useNavigate();
  const [diaryContent, setDiaryContent] = useState('');
  const [diaryContentError, setDiaryContentError] = useState<string | null>(
    null,
  );
  const [diaryGenerateRequest, setDiaryGenerateRequest] =
    useState<ApiRequestStatus>('idle');

  const handleDiarySubmit = async (e: React.FormEvent<HTMLFormElement>) => {
    e.preventDefault();
    if (diaryContent.length < 10) {
      setDiaryContentError('10자 이상으로 입력해주세요!');
      return;
    }

    setDiaryGenerateRequest('loading');
    try {
      const response = await fetch(`${API_BASE_URL}/diaries`, {
        method: 'POST',
        body: JSON.stringify({
          diaryDate: new Date().toLocaleDateString('sv-SE', {
            timeZone: 'Asia/Seoul',
          }),
          sourceText: diaryContent,
        }),
      });

      if (!response.ok) {
        throw new Error('네트워크 에러');
      }

      setDiaryGenerateRequest('success');
      navigate('/diary-generating');
    } catch (error: unknown) {
      if (error instanceof Error) {
        alert(error.message);
      }
      setDiaryGenerateRequest('error');
    }
  };
  return (
    <div>
      <PageHeader
        leftButton={<button>왼</button>}
        title={'새 일기 쓰기'}
        rightButton={null}
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

        <FloatingActionButton
          onClick={() => {}}
          disabled={diaryGenerateRequest === 'loading'}
        />
      </form>
    </div>
  );
};

export default DiaryWritePage;
