import { useEffect, useState } from 'react';
import { useNavigate, useParams } from 'react-router';
import { API_BASE_URL, type ApiRequest } from '../../shared/api';
import { css } from '@emotion/react';
import { theme } from '../../styles/theme';
import harudleLogo from '../../assets/images/harudle-logo.png';
import loadingAnimation from '../../assets/images/loading-animation.webp';
import { throwIfResponseFailed, toUserError } from '../../shared/apiError';

interface SharedDiary {
  title: string;
  diaryDate: string;
  imageUrl: string;
  imageUrlExpiresAt: string;
  createdAt: string;
}

const isRecord = (value: unknown): value is Record<string, unknown> => {
  return typeof value === 'object' && value !== null;
};

const isSharedDiary = (value: unknown): value is SharedDiary => {
  return (
    isRecord(value) &&
    typeof value.title === 'string' &&
    typeof value.diaryDate === 'string' &&
    typeof value.imageUrl === 'string' &&
    typeof value.imageUrlExpiresAt === 'string' &&
    typeof value.createdAt === 'string'
  );
};

const formatDiaryDate = (diaryDate: string): string => {
  return new Intl.DateTimeFormat('ko-KR', {
    year: 'numeric',
    month: 'long',
    day: 'numeric',
    weekday: 'short',
    timeZone: 'Asia/Seoul',
  }).format(new Date(`${diaryDate}T00:00:00+09:00`));
};

const DiarySharePage = () => {
  const { shareId } = useParams();
  const navigate = useNavigate();
  const [sharedDiary, setSharedDiary] = useState<ApiRequest<SharedDiary>>({
    status: 'idle',
  });

  useEffect(() => {
    const getSharedDiary = async (): Promise<void> => {
      setSharedDiary({ status: 'loading' });

      try {
        const response = await fetch(
          `${API_BASE_URL}/public/shares/${shareId}`,
        );

        await throwIfResponseFailed(
          response,
          '공유된 일기를 불러오지 못했습니다.',
        );

        const data: unknown = await response.json();

        if (!isSharedDiary(data)) {
          throw new Error('공유된 일기를 불러오지 못했습니다.');
        }

        setSharedDiary({ status: 'success', data });
      } catch (error: unknown) {
        setSharedDiary({
          status: 'error',
          error: toUserError(error, '공유된 일기를 불러오지 못했습니다.'),
        });
      }
    };

    void getSharedDiary();
  }, [shareId]);

  return (
    <div css={diarySharePageStyle}>
      <button css={logoButtonStyle} onClick={() => navigate('/')}>
        <img src={harudleLogo} alt="하루들" css={logoStyle} />
      </button>

      <main css={sharedDiaryContentStyle}>
        {sharedDiary.status === 'idle' || sharedDiary.status === 'loading' ? (
          <div css={feedbackStyle}>
            <img src={loadingAnimation} alt="로딩 중" css={loadingImageStyle} />
          </div>
        ) : sharedDiary.status === 'error' ? (
          <div css={feedbackStyle}>
            <div>{sharedDiary.error.message}</div>
            <button css={errorButtonStyle} onClick={() => navigate('/')}>
              홈으로 이동
            </button>
          </div>
        ) : (
          <>
            <div css={diaryTitleStyle}>{sharedDiary.data.title}</div>
            <img
              src={sharedDiary.data.imageUrl}
              alt={sharedDiary.data.title}
              css={diaryImageStyle}
            />
            <div css={diaryDateStyle}>
              {formatDiaryDate(sharedDiary.data.diaryDate)}
            </div>
          </>
        )}
      </main>
    </div>
  );
};

export default DiarySharePage;

const diarySharePageStyle = css`
  display: flex;
  flex-direction: column;
  align-items: center;
  width: 100%;
  height: 100%;
  padding-top: 44px;
  overflow: hidden;
  background-color: ${theme.colors.background};
  box-sizing: border-box;
`;

const logoButtonStyle = css`
  display: flex;
  align-items: center;
  width: 200px;
  height: 133px;
  border: none;
  background-color: transparent;
  cursor: pointer;
`;

const logoStyle = css`
  flex-shrink: 0;
  width: 100%;
  height: 100%;
  object-fit: fill;
`;

const sharedDiaryContentStyle = css`
  display: flex;
  flex: 1;
  flex-direction: column;
  align-items: center;
  width: 390px;
  margin-top: -1px;
`;

const diaryTitleStyle = css`
  display: flex;
  align-items: flex-start;
  justify-content: center;
  width: 374px;
  height: 72px;
  color: ${theme.colors.textPrimary};
  font-size: 26px;
  font-weight: 700;
  line-height: 36px;
  text-align: center;
  overflow-wrap: break-word;
`;

const diaryImageStyle = css`
  width: 374px;
  height: 374px;
  margin-top: 20px;
  padding: 2px;
  border: 1px solid ${theme.colors.border};
  border-radius: 16px;
  background-color: ${theme.colors.background};
  object-fit: cover;
  box-sizing: border-box;
`;

const diaryDateStyle = css`
  width: 374px;
  color: ${theme.colors.textSecondary};
  font-size: 15px;
  font-weight: 500;
  line-height: 24px;
  text-align: center;
`;

const feedbackStyle = css`
  flex: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 16px;
  width: 100%;
  color: ${theme.colors.textPrimary};
  font-size: 16px;
  line-height: 26px;
`;

const errorButtonStyle = css`
  width: 140px;
  height: 48px;
  border: none;
  border-radius: 9999px;
  background-color: ${theme.colors.primary};
  color: ${theme.colors.background};
  font-size: 15px;
  font-weight: 500;
  cursor: pointer;
`;

const loadingImageStyle = css`
  width: 160px;
  height: 160px;
`;
