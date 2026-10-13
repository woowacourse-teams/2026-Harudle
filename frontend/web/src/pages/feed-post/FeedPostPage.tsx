import { css } from '@emotion/react';
import { useLocation, useNavigate, useParams } from 'react-router';
import backIcon from '../../assets/icons/back.svg';
import harudleLogo from '../../assets/images/harudle-logo.webp';
import { theme } from '../../styles/theme';
import { isRecord } from '../../shared/utils';
import FeedPostContent from './FeedPostContent';
import { FEED_POST_COPY } from './copy';

interface FeedPostState {
  readonly imageUrl: string;
  readonly publishedFeedId: null;
}

const isFeedPostState = (value: unknown): value is FeedPostState => {
  return (
    isRecord(value) &&
    typeof value.imageUrl === 'string' &&
    value.imageUrl.trim() !== '' &&
    value.publishedFeedId === null
  );
};

const FeedPostPage = () => {
  const { diaryId } = useParams();
  const navigate = useNavigate();
  const state: unknown = useLocation().state;

  return (
    <div css={pageStyle}>
      <header css={headerStyle}>
        <button
          type="button"
          css={logoButtonStyle}
          onClick={() => {
            void navigate('/');
          }}
        >
          <img css={logoStyle} src={harudleLogo} alt="하루들" />
        </button>
        <button
          type="button"
          css={backButtonStyle}
          onClick={() => {
            void navigate(diaryId ? `/diary/${diaryId}` : '/album');
          }}
        >
          <img src={backIcon} alt="뒤로가기 아이콘" />
        </button>
      </header>
      {diaryId && isFeedPostState(state) ? (
        <FeedPostContent diaryId={diaryId} imageUrl={state.imageUrl} />
      ) : (
        <main css={errorStyle}>{FEED_POST_COPY.invalidEntry}</main>
      )}
    </div>
  );
};

export default FeedPostPage;

const pageStyle = css`
  display: flex;
  flex-direction: column;
  height: 100%;
  background-color: ${theme.colors.background.surface};
  color: ${theme.colors.foreground.neutral};
`;

const headerStyle = css`
  position: relative;
  flex-shrink: 0;
  width: 100%;
  height: 64px;
`;

const logoButtonStyle = css`
  display: flex;
  justify-content: center;
  align-items: center;
  width: 100%;
  height: 100%;
  border: none;
  background: none;
  cursor: pointer;
`;

const logoStyle = css`
  width: 106px;
  height: 71px;
`;

const backButtonStyle = css`
  position: absolute;
  top: 12px;
  left: 8px;
  display: flex;
  align-items: center;
  justify-content: center;
  width: 40px;
  height: 40px;
  padding: 0;
  border: none;
  background: none;
  cursor: pointer;
`;

const errorStyle = css`
  padding: 20px;
`;
