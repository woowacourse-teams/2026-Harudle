import { useEffect } from 'react';
import useDiaryDelete from './useDiaryDelete';
import useDiaryDetail from './useDiaryDetail';
import LoadingSpinner from '../../shared/LoadingSpinner';
import DiaryDetailError from './DiaryDetailError';
import PageHeader from '../../shared/PageHeader';
import backIcon from '../../assets/icons/back.svg';
import moreIcon from '../../assets/icons/delete.svg';
import { useNavigate } from 'react-router';
import { css } from '@emotion/react';
import DiaryShareButton from './DiaryShareButton';
import DiaryImageDownloadButton from './DiaryImageDownloadButton';
import { theme } from '../../styles/theme';

const DiaryDetailContent = ({ diaryId }: { diaryId: string }) => {
  const navigate = useNavigate();
  const { request: diaryDetailRequest } = useDiaryDetail({ diaryId });
  const { request: diaryDeleteRequest, execute: deleteDiary } = useDiaryDelete({
    diaryId,
  });

  useEffect(() => {
    if (diaryDeleteRequest.status === 'error') {
      alert(diaryDeleteRequest.error.message);
    }
  }, [diaryDeleteRequest]);

  if (
    diaryDetailRequest.status === 'idle' ||
    diaryDetailRequest.status === 'loading'
  ) {
    return <LoadingSpinner />;
  }

  if (diaryDetailRequest.status === 'error') {
    return <DiaryDetailError errorMessage={diaryDetailRequest.error.message} />;
  }

  const diaryDetail = diaryDetailRequest.data;
  const { imageUrl, title } = diaryDetail.generation;

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
        title={diaryDetail.diaryDate}
        right={
          <button
            type="button"
            aria-label="더보기"
            css={headerButtonStyle}
            onClick={() => {
              const confirmDelete = window.confirm('일기를 삭제할까요?');
              if (confirmDelete) {
                void deleteDiary();
              }
            }}
          >
            <img
              src={moreIcon}
              alt="더보기 아이콘"
              css={headerButtonIconStyle}
            />
          </button>
        }
      />

      <main css={contentStyle}>
        <div css={diaryTitleStyle}>{title}</div>

        <img css={diaryImageStyle} src={imageUrl} alt="그림 일기" />

        <div>
          <span css={storyTitleStyle}>오늘의 이야기</span>
          <p css={storyTextStyle}>{diaryDetail.sourceText}</p>
        </div>

        <DiaryShareButton diaryId={diaryId} diaryTitle={title} />
        <DiaryImageDownloadButton imageUrl={imageUrl} />
      </main>
    </div>
  );
};

export default DiaryDetailContent;

const pageStyle = css`
  position: relative;
  width: 100%;
  height: 100%;
  padding: 20px;
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
  gap: 8px;
  width: 100%;
  height: 100%;
  padding-bottom: 40px;
  box-sizing: border-box;
  overflow-y: auto;
`;

const diaryTitleStyle = css`
  flex-shrink: 0;
  color: ${theme.colors.foreground.neutral};
  font-size: 26px;
  font-weight: 700;
  line-height: 36px;
  text-align: center;
  overflow-wrap: break-word;
`;

const diaryImageStyle = css`
  width: 100%;
  aspect-ratio: 1;
  border-radius: 16px;
  outline: 1px solid ${theme.colors.stroke.outline};
  background-color: transparent;
  object-fit: cover;
`;

const storyTitleStyle = css`
  text-align: center;
  color: ${theme.colors.foreground.neutral};
  font-size: 18px;
  font-weight: 700;
  line-height: 28px;
  white-space: pre-wrap;
  overflow-wrap: break-word;
`;

const storyTextStyle = css`
  flex-shrink: 0;
  margin-top: -2px;
  color: ${theme.colors.foreground.neutral};
  font-size: 16px;
  font-weight: 400;
  line-height: 26px;
  white-space: pre-wrap;
  overflow-wrap: break-word;
`;
