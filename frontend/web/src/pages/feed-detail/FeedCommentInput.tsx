import { css } from '@emotion/react';
import { useRef, useState, type FormEvent } from 'react';
import sendIcon from '../../assets/icons/feed-comment-submit.svg';
import { theme } from '../../styles/theme';
import { FEED_DETAIL_COPY } from './copy';
import type { FeedComment } from '../../domain/feeds/feedComments';
import NeedLoginBottomSheet from '../../shared/NeedLoginBottomSheet';
import type { BottomSheetHandle } from '../../shared/BottomSheet';
import useFeedCommentCreate from './useFeedCommentCreate';

interface FeedCommentInputProps {
  readonly feedId: string;
  readonly onCommentCreated: (comment: FeedComment) => void;
}

const FeedCommentInput = ({
  feedId,
  onCommentCreated,
}: FeedCommentInputProps) => {
  const loginSheetRef = useRef<BottomSheetHandle>(null);
  const [content, setContent] = useState('');
  const { request, execute } = useFeedCommentCreate({ feedId });

  const handleSubmit = (event: FormEvent<HTMLFormElement>): void => {
    event.preventDefault();

    if (localStorage.getItem('harudle.has-completed-oauth') === null) {
      loginSheetRef.current?.open();
      return;
    }

    if (request.status === 'loading') return;

    void execute({
      content,
      onSuccess: (comment) => {
        setContent('');
        onCommentCreated(comment);
      },
    });
  };

  return (
    <>
      <form css={composerStyle} onSubmit={handleSubmit}>
        <div css={inputRowStyle}>
          <textarea
            rows={1}
            placeholder={FEED_DETAIL_COPY.commentPlaceholder}
            css={inputStyle}
            value={content}
            disabled={request.status === 'loading'}
            onChange={(event) => {
              setContent(event.target.value);
            }}
          />
          <button
            type="submit"
            disabled={content.length === 0 || request.status === 'loading'}
            css={sendButtonStyle}
          >
            <span css={sendIconStyle} />
          </button>
        </div>
        {request.status === 'error' && (
          <p css={errorStyle}>{FEED_DETAIL_COPY.commentSubmitError}</p>
        )}
      </form>
      <NeedLoginBottomSheet
        description={FEED_DETAIL_COPY.commentLoginDescription}
        ref={loginSheetRef}
      />
    </>
  );
};

export default FeedCommentInput;

const composerStyle = css`
  flex-shrink: 0;
  padding: 10px 16px max(12px, env(safe-area-inset-bottom));
  border-top: 1px solid ${theme.colors.stroke.divider};
`;
const inputRowStyle = css`
  position: relative;
`;
const inputStyle = css`
  display: block;
  word-break: keep-all;
  width: 100%;
  min-height: 42px;
  max-height: calc(24px * 3 + 16px + 2px);
  field-sizing: content;
  padding: 8px 44px 8px 14px;
  border: 1px solid ${theme.colors.stroke.outline};
  border-radius: 21px;
  outline: none;
  resize: none;
  overflow-y: auto;
  background-color: ${theme.colors.background.neutralSubtle};
  color: ${theme.colors.foreground.neutral};
  font-size: 16px;
  font-weight: 400;
  line-height: 24px;
  transition: all 0.2s ease-in-out;
  scrollbar-width: none;

  &::-webkit-scrollbar {
    display: none;
  }

  &::placeholder {
    color: ${theme.colors.foreground.placeholder};
    opacity: 1;
  }
  &:focus {
    border-color: ${theme.colors.stroke.brandSolid};
    background-color: ${theme.colors.background.surface};
  }
  &:disabled {
    opacity: 0.4;
  }
`;
const sendButtonStyle = css`
  position: absolute;
  bottom: 5px;
  right: 6px;
  display: flex;
  align-items: center;
  justify-content: center;
  width: 34px;
  height: 32px;
  padding: 0;
  border: none;
  background: none;
  cursor: pointer;
  &:disabled {
    opacity: 0.4;
    cursor: default;
  }
`;
const sendIconStyle = css`
  width: 28px;
  height: 28px;
  background-color: ${theme.colors.foreground.brand};
  mask: url(${sendIcon}) center / contain no-repeat;
`;
const errorStyle = css`
  margin-top: 8px;
  color: ${theme.colors.foreground.critical};
  font-size: 12px;
`;
