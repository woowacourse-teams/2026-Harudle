import { useEffect, useState } from 'react';
import { css } from '@emotion/react';
import { useNavigate } from 'react-router';
import loadingAnimation from '../../assets/images/loading-animation.webp';
import { theme } from '../../styles/theme';
import GuestLoginCta from './GuestLoginCta';
import { getKoreanToday, validateGuestDiary } from './guestDiaryValidation';
import { isGuestTrialAlreadyUsedError } from './guestTrialErrors';
import { getGuestDiaryResultPath } from './guestTrialPaths';
import useGuestDiaryCreation from './useGuestDiaryCreation';

const GuestDiaryWritePage = () => {
  const navigate = useNavigate();
  const { creationState, submitDiary, retryDiary } = useGuestDiaryCreation({
    enabled: true,
  });
  const [diaryDate, setDiaryDate] = useState(getKoreanToday);
  const [sourceText, setSourceText] = useState('');
  const [diaryDateError, setDiaryDateError] = useState<string | null>(null);
  const [sourceTextError, setSourceTextError] = useState<string | null>(null);
  const completedDiaryId =
    creationState.status === 'success' ? creationState.data.id : null;

  useEffect(() => {
    if (completedDiaryId) {
      navigate(getGuestDiaryResultPath(completedDiaryId), { replace: true });
    }
  }, [completedDiaryId, navigate]);

  const handleSubmit = (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    const request = { diaryDate, sourceText };
    const errors = validateGuestDiary(request);

    setDiaryDateError(errors.diaryDate ?? null);
    setSourceTextError(errors.sourceText ?? null);

    if (errors.diaryDate || errors.sourceText) {
      return;
    }

    void submitDiary(request);
  };

  if (creationState.status === 'generating') {
    return <LoadingView message="오늘의 이야기를 그림 일기로 만들고 있어요" />;
  }

  if (creationState.status === 'success') {
    return <LoadingView message="완성된 그림 일기를 불러오고 있어요" />;
  }

  if (creationState.status === 'error') {
    if (isGuestTrialAlreadyUsedError(creationState.error)) {
      return (
        <div css={feedbackPageStyle}>
          <h1 css={feedbackTitleStyle}>게스트 체험을 이미 사용했어요</h1>
          <p css={feedbackMessageStyle}>
            로그인하면 하루들의 그림 일기를 계속 만들 수 있어요.
          </p>
          <GuestLoginCta label="카카오로 로그인하기" />
        </div>
      );
    }

    return (
      <div css={feedbackPageStyle}>
        <h1 css={feedbackTitleStyle}>일기를 완성하지 못했어요</h1>
        <p css={feedbackMessageStyle}>{creationState.error.message}</p>
        <button
          type="button"
          css={primaryButtonStyle}
          onClick={() => void retryDiary()}
        >
          같은 요청으로 다시 시도하기
        </button>
      </div>
    );
  }

  return (
    <main css={pageStyle}>
      <header css={headerStyle}>
        <p css={eyebrowStyle}>로그인 없이 한 번 체험하기</p>
        <h1 css={titleStyle}>오늘의 하루를 들려주세요</h1>
        <p css={descriptionStyle}>
          적어주신 이야기로 나만의 네컷 그림 일기를 만들어드려요.
        </p>
      </header>

      <form css={formStyle} onSubmit={handleSubmit}>
        <label css={fieldStyle}>
          <span css={labelStyle}>일기 날짜</span>
          <input
            type="date"
            value={diaryDate}
            max={getKoreanToday()}
            css={inputStyle(diaryDateError !== null)}
            aria-describedby={
              diaryDateError ? 'guest-diary-date-error' : undefined
            }
            onChange={(event) => {
              setDiaryDate(event.target.value);
              setDiaryDateError(null);
            }}
          />
          {diaryDateError ? (
            <span id="guest-diary-date-error" css={errorStyle}>
              {diaryDateError}
            </span>
          ) : null}
        </label>

        <label css={fieldStyle}>
          <span css={labelStyle}>오늘의 이야기</span>
          <textarea
            value={sourceText}
            maxLength={300}
            css={textAreaStyle(sourceTextError !== null)}
            aria-describedby={
              sourceTextError ? 'guest-diary-source-text-error' : undefined
            }
            placeholder="오늘 있었던 일과 그때의 기분을 자유롭게 적어주세요."
            onChange={(event) => {
              setSourceText(event.target.value);
              setSourceTextError(null);
            }}
          />
          <span css={descriptionRowStyle}>
            <span id="guest-diary-source-text-error" css={errorStyle}>
              {sourceTextError}
            </span>
            <span css={countStyle}>{Array.from(sourceText).length} / 300</span>
          </span>
        </label>

        <button type="submit" css={primaryButtonStyle}>
          그림 일기 만들기
        </button>
      </form>
    </main>
  );
};

export default GuestDiaryWritePage;

const LoadingView = ({ message }: { message: string }) => {
  return (
    <div css={feedbackPageStyle}>
      <img src={loadingAnimation} alt="로딩 중" css={loadingImageStyle} />
      <p css={feedbackTitleStyle}>{message}</p>
      <p css={feedbackMessageStyle}>완성될 때까지 잠시만 기다려주세요.</p>
    </div>
  );
};

const pageStyle = css`
  display: flex;
  flex-direction: column;
  gap: 28px;
  width: 100%;
  min-height: 100%;
  padding: 36px 20px 28px;
  overflow-y: auto;
  background-color: #ffffff;
`;

const headerStyle = css`
  display: flex;
  flex-direction: column;
  gap: 8px;
`;

const eyebrowStyle = css`
  color: ${theme.colors.text.brand};
  font-size: 14px;
  font-weight: 700;
  line-height: 22px;
`;

const titleStyle = css`
  color: ${theme.colors.text.primary};
  font-size: 28px;
  font-weight: 700;
  line-height: 40px;
`;

const descriptionStyle = css`
  color: ${theme.colors.text.secondary};
  font-size: 15px;
  font-weight: 400;
  line-height: 24px;
  word-break: keep-all;
`;

const formStyle = css`
  display: flex;
  flex-direction: column;
  gap: 22px;
`;

const fieldStyle = css`
  display: flex;
  flex-direction: column;
  gap: 8px;
`;

const labelStyle = css`
  color: ${theme.colors.text.primary};
  font-size: 16px;
  font-weight: 700;
  line-height: 24px;
`;

const inputStyle = (hasError: boolean) => css`
  width: 100%;
  height: 52px;
  padding: 0 16px;
  border: 1px solid
    ${hasError ? theme.colors.text.danger : theme.colors.border.primary};
  border-radius: 16px;
  outline: none;
  background-color: #ffffff;
  color: ${theme.colors.text.primary};
  font-size: 16px;

  &:focus {
    border-color: ${
      hasError ? theme.colors.text.danger : theme.colors.bg.brand
    };
  }
`;

const textAreaStyle = (hasError: boolean) => css`
  width: 100%;
  min-height: 220px;
  padding: 18px;
  border: 1px solid
    ${hasError ? theme.colors.text.danger : theme.colors.border.primary};
  border-radius: 20px;
  outline: none;
  resize: vertical;
  background-color: #ffffff;
  color: ${theme.colors.text.primary};
  font-size: 15px;
  line-height: 26px;

  &::placeholder {
    color: ${theme.colors.text.secondary};
  }

  &:focus {
    border-color: ${
      hasError ? theme.colors.text.danger : theme.colors.bg.brand
    };
  }
`;

const descriptionRowStyle = css`
  display: flex;
  justify-content: space-between;
  gap: 12px;
  min-height: 22px;
`;

const errorStyle = css`
  color: ${theme.colors.text.danger};
  font-size: 13px;
  line-height: 20px;
`;

const countStyle = css`
  flex-shrink: 0;
  color: ${theme.colors.text.secondary};
  font-size: 13px;
  line-height: 20px;
`;

const primaryButtonStyle = css`
  width: 100%;
  min-height: 56px;
  padding: 14px 20px;
  border: none;
  border-radius: 18px;
  background-color: ${theme.colors.bg.brand};
  color: #ffffff;
  font-size: 16px;
  font-weight: 700;
  line-height: 24px;
  cursor: pointer;

  &:active {
    transform: scale(0.98);
  }
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
