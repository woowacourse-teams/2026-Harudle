import { DIARY_GENERATING_COPY } from './copy';
import { css } from '@emotion/react';
import diaryGeneratingFailImage from '../../assets/images/diary-generating-fail.png';
import { theme } from '../../styles/theme';
import PageHeader from '../../shared/PageHeader';
import backIcon from '../../assets/icons/back.svg';
import { useEffect } from 'react';
import { RequestError } from '../../shared/api';
import { DIARY_GENERATION_ERROR_CODE } from '../../domain/diary/diaryGenerate';

const DiaryGeneratingError = ({
  error,
  onReturnHome,
  onDiaryWriteRetry,
}: {
  error: Error;
  onReturnHome: () => void;
  onDiaryWriteRetry: () => void;
}) => {
  const isGenerationInProgress =
    error instanceof RequestError &&
    error.problem.code === DIARY_GENERATION_ERROR_CODE.IN_PROGRESS;
  const isDailyLimitExceeded =
    error instanceof RequestError &&
    error.problem.code === DIARY_GENERATION_ERROR_CODE.DAILY_LIMIT_EXCEEDED;

  useEffect(() => {
    if (!isGenerationInProgress) {
      return;
    }

    // alert를 렌더링 도중에 실행시키지 않기 위해 useEffect로 감싼다. (순수성 보장)
    alert(DIARY_GENERATING_COPY.inProgress);
    onReturnHome();
  }, [isGenerationInProgress, onReturnHome]);

  if (isGenerationInProgress) {
    return null;
  }

  return (
    <div css={diaryGeneratingErrorStyle} role="alert">
      <PageHeader
        left={
          <button
            type="button"
            aria-label="뒤로 가기"
            css={headerButtonStyle}
            onClick={onReturnHome}
          >
            <img
              src={backIcon}
              alt="뒤로가기 아이콘"
              css={headerButtonIconStyle}
            />
          </button>
        }
        title={null}
        right={null}
      />

      <img
        src={diaryGeneratingFailImage}
        alt="네컷만화를 완성하지 못해 속상한 하루들 캐릭터"
        css={illustrationStyle}
      />

      <div css={messageBoxStyle}>
        <h2 css={titleStyle}>{DIARY_GENERATING_COPY.errorTitle}</h2>
        <p css={descriptionStyle}>
          {isDailyLimitExceeded
            ? DIARY_GENERATING_COPY.limitErrorDescription
            : DIARY_GENERATING_COPY.errorDescription}
        </p>
        <p css={descriptionStyle}>{error.message}</p>
      </div>

      <button type="button" css={retryButtonStyle} onClick={onDiaryWriteRetry}>
        {DIARY_GENERATING_COPY.editAction}
      </button>
    </div>
  );
};

export default DiaryGeneratingError;

const diaryGeneratingErrorStyle = css`
  display: flex;
  flex-direction: column;
  justify-content: start;
  align-items: center;
  gap: 16px;
  width: 100%;
  height: 100%;
  padding: 24px 20px;
  overflow-y: auto;
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

const illustrationStyle = css`
  width: 280px;
  height: 280px;
  object-fit: contain;
`;

const messageBoxStyle = css`
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 8px;
  text-align: center;
`;

const titleStyle = css`
  color: ${theme.colors.foreground.neutral};
  font-size: 20px;
  font-weight: 700;
  line-height: 30px;
`;

const descriptionStyle = css`
  max-width: 280px;
  color: ${theme.colors.foreground.neutralMuted};
  font-size: 14px;
  font-weight: 400;
  line-height: 22px;
  word-break: keep-all;
`;

const retryButtonStyle = css`
  min-width: 200px;
  height: 48px;
  border: none;
  border-radius: 18px;
  background-color: ${theme.colors.background.brandSolid};
  color: ${theme.colors.foreground.onBrand};
  font-size: 15px;
  font-weight: 600;
  cursor: pointer;

  &:active {
    transform: scale(0.98);
  }
`;
