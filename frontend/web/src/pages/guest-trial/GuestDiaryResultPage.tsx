import { css } from '@emotion/react';
import { useParams } from 'react-router';
import loadingAnimation from '../../assets/images/loading-animation.webp';
import { theme } from '../../styles/theme';
import GuestLoginCta from './GuestLoginCta';
import { isGuestTrialAlreadyUsedError } from './guestTrialErrors';
import useGuestDiaryResult from './useGuestDiaryResult';

const GuestDiaryResultPage = () => {
  const { diaryId } = useParams();
  const { resultRequest } = useGuestDiaryResult({ diaryId });

  if (resultRequest.status === 'idle' || resultRequest.status === 'loading') {
    return (
      <div css={feedbackPageStyle}>
        <img src={loadingAnimation} alt="로딩 중" css={loadingImageStyle} />
        <p css={feedbackTitleStyle}>완성된 그림 일기를 불러오고 있어요</p>
      </div>
    );
  }

  if (resultRequest.status === 'error') {
    return (
      <div css={feedbackPageStyle}>
        <h1 css={feedbackTitleStyle}>
          {isGuestTrialAlreadyUsedError(resultRequest.error)
            ? '게스트 체험을 이미 사용했어요'
            : '그림 일기를 불러오지 못했어요'}
        </h1>
        <p css={feedbackMessageStyle}>{resultRequest.error.message}</p>
        {isGuestTrialAlreadyUsedError(resultRequest.error) ? (
          <GuestLoginCta label="카카오로 로그인하기" />
        ) : null}
      </div>
    );
  }

  const diary = resultRequest.data;

  return (
    <main css={pageStyle}>
      <header css={headerStyle}>
        <p css={dateStyle}>{diary.diaryDate}</p>
        <h1 css={titleStyle}>{diary.generation.title}</h1>
      </header>

      <img
        src={diary.generation.imageUrl}
        alt={`${diary.generation.title} 그림 일기`}
        css={diaryImageStyle}
      />

      <section css={storyStyle}>
        <h2 css={storyTitleStyle}>오늘의 이야기</h2>
        <p css={storyTextStyle}>{diary.sourceText}</p>
      </section>

      <section css={ctaSectionStyle}>
        <p css={ctaDescriptionStyle}>
          로그인하고 오늘의 이야기를 계속 그림 일기로 만들어보세요
        </p>
        <GuestLoginCta label="카카오로 로그인하기" />
      </section>
    </main>
  );
};

export default GuestDiaryResultPage;

const pageStyle = css`
  display: flex;
  flex-direction: column;
  gap: 20px;
  width: 100%;
  min-height: 100%;
  padding: 28px 20px 36px;
  overflow-y: auto;
  background-color: #ffffff;
`;

const headerStyle = css`
  display: flex;
  flex-direction: column;
  gap: 4px;
  text-align: center;
`;

const dateStyle = css`
  color: ${theme.colors.text.secondary};
  font-size: 14px;
  line-height: 22px;
`;

const titleStyle = css`
  color: ${theme.colors.text.primary};
  font-size: 26px;
  font-weight: 700;
  line-height: 38px;
  overflow-wrap: anywhere;
`;

const diaryImageStyle = css`
  width: 100%;
  aspect-ratio: 1;
  border: 1px solid ${theme.colors.border.primary};
  border-radius: 20px;
  object-fit: cover;
`;

const storyStyle = css`
  display: flex;
  flex-direction: column;
  gap: 8px;
`;

const storyTitleStyle = css`
  color: ${theme.colors.text.primary};
  font-size: 18px;
  font-weight: 700;
  line-height: 28px;
`;

const storyTextStyle = css`
  color: ${theme.colors.text.primary};
  font-size: 16px;
  line-height: 26px;
  white-space: pre-wrap;
  overflow-wrap: anywhere;
`;

const ctaSectionStyle = css`
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 12px;
  margin-top: 8px;
  padding-top: 20px;
  border-top: 1px solid ${theme.colors.border.primary};
`;

const ctaDescriptionStyle = css`
  color: ${theme.colors.text.secondary};
  font-size: 14px;
  line-height: 22px;
  text-align: center;
  word-break: keep-all;
`;

const feedbackPageStyle = css`
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 16px;
  width: 100%;
  min-height: 100%;
  padding: 32px 24px;
  background-color: #ffffff;
  text-align: center;
`;

const loadingImageStyle = css`
  width: 140px;
  height: 140px;
`;

const feedbackTitleStyle = css`
  color: ${theme.colors.text.primary};
  font-size: 22px;
  font-weight: 700;
  line-height: 34px;
  word-break: keep-all;
`;

const feedbackMessageStyle = css`
  color: ${theme.colors.text.secondary};
  font-size: 15px;
  line-height: 24px;
  word-break: keep-all;
`;
