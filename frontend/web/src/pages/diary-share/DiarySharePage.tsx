import { css } from '@emotion/react';
import { theme } from '../../styles/theme';
import harudleLogo from '../../assets/images/harudle-logo.png';
import { useNavigate, useParams } from 'react-router';
import useDiaryShare from './useDiaryShare';
import loadingAnimation from '../../assets/images/loading-animation.webp';
import { useEffect } from 'react';
import { useAnalytics } from '../../shared/useAnalytics';

const DiarySharePage = () => {
  const navigate = useNavigate();
  const { shareId } = useParams();
  const { sharedDiaryRequest } = useDiaryShare({ shareId });
  const { track } = useAnalytics();

  useEffect(() => {
    if (sharedDiaryRequest.status === 'success' && shareId) {
      track('diary_share_viewed', { share_id: shareId });
    }
  }, [sharedDiaryRequest.status, shareId, track]);

  const handleLandingClick = () => {
    if (shareId) {
      track('diary_share_landing_clicked', { share_id: shareId });
    }

    navigate('/');
  };

  if (
    sharedDiaryRequest.status === 'idle' ||
    sharedDiaryRequest.status === 'loading'
  ) {
    return (
      <div css={loadingAnimationBoxStyle}>
        <img src={loadingAnimation} alt="로딩 중" css={loadingImageStyle} />
      </div>
    );
  }

  if (sharedDiaryRequest.status === 'error') {
    return <div>{sharedDiaryRequest.error.message}</div>;
  }
  const { title, imageUrl, diaryDate } = sharedDiaryRequest.data;

  return (
    <div css={diarySharePageStyle}>
      <button css={logoButtonStyle} onClick={handleLandingClick}>
        <img src={harudleLogo} alt="하루들" css={logoStyle} />
      </button>

      <main css={sharedDiaryContentStyle}>
        <div css={diaryTitleStyle}>{title}</div>
        <img src={imageUrl} alt={title} css={diaryImageStyle} />
        <div css={diaryDateStyle}>{diaryDate}</div>
      </main>
    </div>
  );
};

export default DiarySharePage;

const diarySharePageStyle = css`
  display: flex;
  flex-direction: column;
  justify-content: center;
  align-items: center;
  width: 100%;
  height: 100%;
  padding-top: 44px;
  overflow: auto;
  background-color: #ffffff;
`;

const logoButtonStyle = css`
  display: flex;
  align-items: center;
  width: 200px;
  height: 133px;
  border: none;
  background-color: transparent;
  cursor: pointer;

  &:active {
    transform: scale(0.98);
  }
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
`;

const diaryTitleStyle = css`
  display: flex;
  align-items: flex-start;
  justify-content: center;
  width: 374px;
  height: 72px;
  color: ${theme.colors.text.primary};
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
  border-radius: 16px;
  background-color: #ffffff;
  object-fit: cover;
  box-sizing: border-box;
`;

const diaryDateStyle = css`
  width: 374px;
  color: ${theme.colors.text.secondary};
  font-size: 15px;
  font-weight: 500;
  line-height: 24px;
  text-align: center;
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
