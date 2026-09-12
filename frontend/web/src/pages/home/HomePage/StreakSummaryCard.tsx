import { css } from '@emotion/react';
import { theme } from '../../../styles/theme';
import useCurrentStreak from './useCurrentStreak';
import type { ApiRequest } from '../../../shared/api';
import dogStreakCharacter from '../../../assets/images/dog-streak-diary.png';
import type { CurrentStreak } from '../../../domain/currentStreak';

const StreakSummaryCard = () => {
  const { request } = useCurrentStreak();

  const message = getStreakMessage(request);

  return (
    <section
      css={streakCardStyle}
      aria-label="연속 기록"
      aria-live="polite"
      aria-busy={request.status === 'idle' || request.status === 'loading'}
    >
      <div css={streakCharacterFrameStyle}>
        <img
          src={dogStreakCharacter}
          alt="연속 기록을 이어가는 강아지"
          css={streakCharacterStyle}
        />
      </div>
      <div css={streakCopyStyle}>
        <p css={streakTitleStyle}>
          <span>연속</span>
          <strong>{message.count}일째</strong>
        </p>
        <p css={streakDescriptionStyle}>{message.description}</p>
      </div>
    </section>
  );
};

export default StreakSummaryCard;

const streakCardStyle = css`
  flex-shrink: 0;
  display: flex;
  align-items: center;
  gap: 10px;
  width: 100%;
  min-height: 84px;
  padding: 4px 12px 4px 8px;
  border: 1px solid ${theme.colors.border.primary};
  border-radius: 16px;
  background-color: #faf6fe;
`;

const streakCharacterFrameStyle = css`
  flex: 0 0 clamp(62px, 17vw, 72px);
  width: clamp(62px, 17vw, 72px);
  height: clamp(62px, 17vw, 72px);
  align-self: center;
  overflow: hidden;
  display: flex;
  align-items: center;
  justify-content: center;
  transform: translateY(-3px);
`;

const streakCharacterStyle = css`
  width: 100%;
  height: 100%;
  object-fit: contain;
`;

const streakCopyStyle = css`
  min-width: 0;
  transform: translateY(2px);
`;

const streakTitleStyle = css`
  display: flex;
  align-items: baseline;
  gap: 8px;
  color: ${theme.colors.text.primary};
  font-size: 16px;
  font-weight: 500;
  line-height: 20px;

  strong {
    color: ${theme.colors.text.brand};
    font-size: 16px;
    font-weight: 700;
  }
`;

const streakDescriptionStyle = css`
  margin-top: 4px;
  color: ${theme.colors.text.secondary};
  font-size: 14px;
  font-weight: 500;
  line-height: 20px;
`;

const getStreakMessage = (request: ApiRequest<CurrentStreak>) => {
  if (request.status === 'idle' || request.status === 'loading') {
    return {
      count: '-',
      description: '기록을 불러오고 있어요.',
    };
  }

  if (request.status === 'error') {
    return {
      count: '-',
      description: '잠시 후 다시 확인해 주세요.',
    };
  }

  const { streakCount, recordedToday } = request.data;

  if (streakCount === 0) {
    return {
      count: '0',
      description: '오늘부터 기록을 시작해 볼까요?',
    };
  }

  return {
    count: String(streakCount),
    description: recordedToday
      ? '오늘도 기록을 이어갔어요!'
      : '오늘도 이어가 볼까요?',
  };
};
