import { FEED_COPY } from './copy';
import { css } from '@emotion/react';
import requestFailImage from '../../assets/images/request-fail.png';
import ActionButton from '../../shared/ActionButton';
import { theme } from '../../styles/theme';

interface FeedErrorProps {
  readonly errorMessage: string;
}

const FeedError = ({ errorMessage }: FeedErrorProps) => {
  return (
    <div css={feedErrorStyle} role="alert">
      <img
        src={requestFailImage}
        alt="피드를 불러오지 못해 속상한 사람과 강아지"
        css={illustrationStyle}
      />

      <div css={messageBoxStyle}>
        <h2 css={titleStyle}>{FEED_COPY.loadErrorTitle}</h2>
        <p css={descriptionStyle}>{errorMessage}</p>
      </div>

      <ActionButton
        label={FEED_COPY.reloadAction}
        onClick={(): void => {
          window.location.reload();
        }}
      />
    </div>
  );
};

export default FeedError;

const feedErrorStyle = css`
  display: flex;
  flex-direction: column;
  justify-content: center;
  align-items: center;
  gap: 16px;
  width: 100%;
  height: 100%;
  padding: 24px 20px;
`;

const illustrationStyle = css`
  width: 240px;
  height: 300px;
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
  word-break: keep-all;
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
