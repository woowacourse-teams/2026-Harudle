import { beforeEach, describe, expect, it, jest } from '@jest/globals';
import { fireEvent, render, screen } from '@testing-library/react';
import FeedCommentInput from './FeedCommentInput';
import type useFeedCommentCreate from './useFeedCommentCreate';
import { FEED_DETAIL_COPY } from './copy';
import { ERROR_MESSAGES } from '../../shared/errorMessage';

const mockExecute =
  jest.fn<ReturnType<typeof useFeedCommentCreate>['execute']>();
let mockRequest: ReturnType<typeof useFeedCommentCreate>['request'] = {
  status: 'idle',
};

jest.mock('./useFeedCommentCreate', () => ({
  __esModule: true,
  default: () => ({ request: mockRequest, execute: mockExecute }),
}));
jest.mock('../../shared/NeedLoginBottomSheet', () => ({
  __esModule: true,
  default: () => null,
}));
jest.mock('../../assets/icons/feed-comment-submit.svg', () => 'send.svg');

beforeEach((): void => {
  localStorage.clear();
  localStorage.setItem('harudle.has-completed-oauth', 'true');
  mockExecute.mockReset();
  mockRequest = { status: 'idle' };
});

describe('댓글 입력 검증', () => {
  it.each(['   ', '가'.repeat(101), '😀'.repeat(101)])(
    '잘못된 입력은 제출을 막고 입력 조건을 안내한다: %s',
    (content) => {
      const { container } = render(
        <FeedCommentInput feedId="feed-id" onCommentCreated={jest.fn()} />,
      );
      fireEvent.change(screen.getByRole('textbox'), {
        target: { value: content },
      });
      expect(screen.getByRole('button')).toBeDisabled();
      expect(screen.getByRole('alert')).toHaveTextContent(
        ERROR_MESSAGES.INVALID_COMMENT_CONTENT,
      );
      const form = container.querySelector('form');
      if (!form) throw new Error('댓글 입력 폼이 없습니다.');
      fireEvent.submit(form);
      expect(mockExecute).not.toHaveBeenCalled();

      fireEvent.change(screen.getByRole('textbox'), {
        target: { value: '수정한 댓글' },
      });
      expect(screen.queryByRole('alert')).not.toBeInTheDocument();
      fireEvent.click(screen.getByRole('button'));
      expect(mockExecute).toHaveBeenCalledWith({
        content: '수정한 댓글',
        onSuccess: expect.any(Function),
      });
    },
  );

  it('빈 입력에는 오류를 표시하지 않고 100 code point 입력은 제출할 수 있다', () => {
    render(<FeedCommentInput feedId="feed-id" onCommentCreated={jest.fn()} />);
    expect(screen.getByRole('button')).toBeDisabled();
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
    const content = `  ${'😀'.repeat(100)}  `;
    fireEvent.change(screen.getByRole('textbox'), {
      target: { value: content },
    });
    expect(screen.getByRole('button')).toBeEnabled();
    fireEvent.click(screen.getByRole('button'));
    expect(mockExecute).toHaveBeenCalledWith({
      content,
      onSuccess: expect.any(Function),
    });
  });

  it('유효한 입력의 요청 실패에는 재시도 안내를 표시한다', () => {
    mockRequest = { status: 'error', error: new Error('network error') };
    render(<FeedCommentInput feedId="feed-id" onCommentCreated={jest.fn()} />);
    fireEvent.change(screen.getByRole('textbox'), {
      target: { value: '댓글' },
    });
    expect(screen.getByRole('alert')).toHaveTextContent(
      FEED_DETAIL_COPY.commentSubmitError,
    );
    expect(screen.getByRole('button')).toBeEnabled();
  });
});
