import ActionButton from '../../shared/ActionButton';
import PageHeader from '../../shared/PageHeader';
import { useEffect, useState } from 'react';
import { useNavigate, useParams } from 'react-router';
import { API_BASE_URL, type ApiRequest } from '../../shared/api';

interface DiaryDetail {
  id: string;
  diaryDate: string;
  sourceText: string;
  createdAt: string;
  diary: {
    id: string;
    status: 'SUCCEEDED';
    title: string;
    imageUrl: string;
    imageUrlExpiresAt: string;
    completedAt: string;
  };
}

const isRecord = (value: unknown): value is Record<string, unknown> => {
  return typeof value === 'object' && value !== null;
};

const isDiaryDetail = (value: unknown): value is DiaryDetail => {
  return (
    isRecord(value) &&
    typeof value.id === 'string' &&
    typeof value.diaryDate === 'string' &&
    typeof value.sourceText === 'string' &&
    typeof value.createdAt === 'string' &&
    isRecord(value.diary) &&
    typeof value.diary.id === 'string' &&
    value.diary.status === 'SUCCEEDED' &&
    typeof value.diary.title === 'string' &&
    typeof value.diary.imageUrl === 'string' &&
    typeof value.diary.imageUrlExpiresAt === 'string' &&
    typeof value.diary.completedAt === 'string'
  );
};

const DiaryDetailPage = () => {
  const navigate = useNavigate();
  const { diaryId } = useParams();
  const [diaryDetail, setDiaryDetail] = useState<ApiRequest<DiaryDetail>>({
    status: 'idle',
  });

  useEffect(() => {
    const getDiaryDetail = async (): Promise<void> => {
      setDiaryDetail({ status: 'loading' });

      try {
        const response = await fetch(`${API_BASE_URL}/diaries/${diaryId}`);

        if (!response.ok) {
          throw new Error('일기를 불러오지 못했습니다.');
        }

        const data: unknown = await response.json();

        if (!isDiaryDetail(data)) {
          throw new Error('일기 상세 응답 형식이 일치하지 않습니다.');
        }

        setDiaryDetail({ status: 'success', data });
      } catch (error: unknown) {
        setDiaryDetail({
          status: 'error',
          error:
            error instanceof Error
              ? error
              : new Error('알 수 없는 에러가 발생했습니다.'),
        });
      }
    };

    void getDiaryDetail();
  }, [diaryId]);

  if (diaryDetail.status === 'idle' || diaryDetail.status === 'loading') {
    return <div>로딩중...</div>;
  }

  if (diaryDetail.status === 'error') {
    return <div>{diaryDetail.error.message}</div>;
  }

  return (
    <div>
      <PageHeader
        leftButton={<button onClick={() => navigate(-1)}>뒤로가기</button>}
        title={diaryDetail.data.diaryDate}
        rightButton={<button>햄버거</button>}
      />
      <div>{diaryDetail.data.diary.title}</div>
      <img
        src={diaryDetail.data.diary.imageUrl}
        alt={diaryDetail.data.diary.title}
      />
      <div>오늘의 이야기</div>
      <div>{diaryDetail.data.sourceText}</div>
      <ActionButton onClick={() => {}} label="공유하기" />
      <ActionButton onClick={() => {}} label="이미지 저장" />
    </div>
  );
};

export default DiaryDetailPage;
