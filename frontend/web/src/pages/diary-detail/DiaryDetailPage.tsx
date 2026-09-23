import { useNavigate, useParams } from 'react-router';
import PageHeader from '../../shared/PageHeader';
import backIcon from '../../assets/icons/back.svg';
import moreIcon from '../../assets/icons/delete.svg';
import { css } from '@emotion/react';
import useDiaryDetail from './useDiaryDetail';
import loadingAnimation from '../../assets/images/loading-animation.webp';
import { theme } from '../../styles/theme';
import useDiaryDelete from './useDiaryDelete';
import DiaryShareButton from './DiaryShareButton';
import DiaryImageDownloadButton from './DiaryImageDownloadButton';
import DiaryDetailError from './DiaryDetailError';

const DiaryDetailPage = () => {
  const navigate = useNavigate();
  const { diaryId } = useParams();
  const { diaryDetailReqeust } = useDiaryDetail({ diaryId });
  const { diaryDeleteRequest, handleDiaryDelete } = useDiaryDelete({ diaryId });

  if (
    diaryDetailReqeust.status === 'idle' ||
    diaryDetailReqeust.status === 'loading'
  ) {
    return (
      <div css={loadingAnimationBoxStyle}>
        <img src={loadingAnimation} alt="로딩 중" css={loadingImageStyle} />
      </div>
    );
  }

  if (diaryDetailReqeust.status === 'error') {
    return <DiaryDetailError errorMessage={diaryDetailReqeust.error.message} />;
  }

  if (diaryDeleteRequest.status === 'error') {
    alert(diaryDeleteRequest.error.message);
  }

  const diaryDetail = diaryDetailReqeust.data;
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
                void handleDiaryDelete();
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

export default DiaryDetailPage;

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

const loadingAnimationBoxStyle = css`
  display: flex;
  justify-content: center;
  align-items: center;
  width: 100%;
  height: 100%;
`;

const loadingImageStyle = css`
  width: 140px;
  height: 140px;
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
  color: ${theme.colors.text.primary};
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
  outline: 1px solid ${theme.colors.border};
  background-color: transparent;
  object-fit: cover;
`;

const storyTitleStyle = css`
  text-align: center;
  color: ${theme.colors.text.primary};
  font-size: 18px;
  font-weight: 700;
  line-height: 28px;
  white-space: pre-wrap;
  overflow-wrap: break-word;
`;

const storyTextStyle = css`
  flex-shrink: 0;
  margin-top: -2px;
  color: ${theme.colors.text.primary};
  font-size: 16px;
  font-weight: 400;
  line-height: 26px;
  white-space: pre-wrap;
  overflow-wrap: break-word;
`;
