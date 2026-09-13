import { useEffect } from 'react';
import useSharedDiary from './useSharedDiary';
import LoadingSpinner from '../../shared/LoadingSpinner';
import SharedDiaryError from './SharedDiaryError';
import { css } from '@emotion/react';
import { theme } from '../../styles/theme';
import { useNavigate } from 'react-router';
import harudleLogo from '../../assets/images/harudle-logo.png';
import { useAnalytics } from '../../posthog/useAnalytics';

const SharedDiaryContent = ({ shareId }: { shareId: string }) => {
  const navigate = useNavigate();
  const { request } = useSharedDiary({ shareId });
  const { track } = useAnalytics();

  useEffect(() => {
    if (request.status === 'success' && shareId) {
      track('diary_share_viewed', { share_id: shareId });
    }
  }, [request.status, shareId, track]);

  const handleLandingClick = () => {
    if (shareId) {
      track('diary_share_landing_clicked', { share_id: shareId });
    }

    navigate('/');
  };

  if (request.status === 'idle' || request.status === 'loading') {
    return <LoadingSpinner />;
  }

  if (request.status === 'error') {
    return <SharedDiaryError errorMessage={request.error.message} />;
  }
  const { title, imageUrl, diaryDate } = request.data;

  return (
    <div css={SharedDiaryPageStyle}>
      <button css={logoButtonStyle} onClick={handleLandingClick}>
        <img src={harudleLogo} alt="하루들" css={logoStyle} />
      </button>
      <p css={logoHintStyle}>로고를 눌러 하루들을 시작해 보세요</p>

      <main css={sharedDiaryContentStyle}>
        <div css={diaryTitleStyle}>{title}</div>
        <img src={imageUrl} alt={title} css={diaryImageStyle} />
        <div css={diaryDateStyle}>{diaryDate}</div>
      </main>
    </div>
  );
};

export default SharedDiaryContent;

const SharedDiaryPageStyle = css`
  display: flex;
  flex-direction: column;
  justify-content: center;
  align-items: center;
  width: 100%;
  height: 100%;
  padding-top: 44px;
  overflow: auto;
  background-color: ${theme.colors.background.surface};
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

const logoHintStyle = css`
  margin: 0 0 24px;
  color: ${theme.colors.foreground.neutralMuted};
  font-size: 13px;
  line-height: 20px;
  text-align: center;
`;

const sharedDiaryContentStyle = css`
  display: flex;
  flex: 1;
  flex-direction: column;
  align-items: center;
  width: 390px;
  padding-bottom: 32px;
`;

const diaryTitleStyle = css`
  display: flex;
  align-items: flex-start;
  justify-content: center;
  width: 374px;
  height: 72px;
  color: ${theme.colors.foreground.neutral};
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
  background-color: ${theme.colors.background.surface};
  object-fit: cover;
  box-sizing: border-box;
`;

const diaryDateStyle = css`
  width: 374px;
  color: ${theme.colors.foreground.neutralMuted};
  font-size: 15px;
  font-weight: 500;
  line-height: 24px;
  text-align: center;
`;
