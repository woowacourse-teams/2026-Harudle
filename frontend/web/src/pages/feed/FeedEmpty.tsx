import { FEED_COPY } from './copy';
import { useNavigate } from 'react-router';
import ActionButton from '../../shared/ActionButton';
import emptyPersonAndDog from '../../assets/images/empty-person-and-dog.png';
import { css } from '@emotion/react';
import { theme } from '../../styles/theme';

const FeedEmpty = () => {
  const navigate = useNavigate();

  return (
    <div css={emptyStateStyle}>
      <div css={emptyStateContentStyle}>
        <img
          src={emptyPersonAndDog}
          alt="사람과 강아지 일러스트"
          css={illustrationStyle}
        />

        <div css={emptyStateDescriptionContentStyle}>
          <div css={emptyStateTitleStyle}>{FEED_COPY.emptyTitle}</div>
          <div css={emptyStateDescriptionStyle}>
            {FEED_COPY.emptyDescription}
          </div>
        </div>
      </div>

      <ActionButton
        label={FEED_COPY.chooseAction}
        onClick={(): void => {
          void navigate('/album');
        }}
      />
    </div>
  );
};

export default FeedEmpty;

const emptyStateStyle = css`
  display: flex;
  flex: 1;
  flex-direction: column;
  justify-content: center;
  align-items: center;
  width: 100%;
  height: 100%;
`;

const emptyStateContentStyle = css`
  display: flex;
  flex-shrink: 0;
  flex-direction: column;
  align-items: center;
  width: 100%;
`;

const illustrationStyle = css`
  width: 300px;
  height: 230px;
  object-fit: cover;
`;

const emptyStateDescriptionContentStyle = css`
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 8px;
  width: 100%;
  margin-bottom: 30px;
  text-align: center;
`;

const emptyStateTitleStyle = css`
  word-break: keep-all;
  width: 100%;
  color: ${theme.colors.foreground.neutral};
  font-size: 22px;
  font-weight: 700;
  line-height: 32px;
`;

const emptyStateDescriptionStyle = css`
  width: 100%;
  color: ${theme.colors.foreground.neutralMuted};
  font-size: 15px;
  font-weight: 400;
  word-break: keep-all;
`;
