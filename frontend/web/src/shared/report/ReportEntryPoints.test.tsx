import { beforeEach, describe, expect, it, jest } from '@jest/globals';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import * as reports from '../../domain/reports/reportSubmission';
import FeedOptionsButton from '../feed/FeedOptionsButton';
import FeedCommentOptionsButton from '../../pages/feed-detail/FeedCommentOptionsButton';
import FeedCommentItem from '../../pages/feed-detail/FeedCommentItem';
import { REPORT_COPY } from './copy';

jest.mock('react-router', () => ({ useNavigate: () => jest.fn() }));
jest.mock('../../posthog/useErrorTracking', () => ({
  useErrorTracking: () => ({ captureError: jest.fn() }),
}));
jest.mock('../../assets/icons/feed-more.svg', () => 'more.svg');
jest.mock('../../assets/icons/delete.svg', () => 'delete.svg');
jest.mock('../../assets/icons/bottom-sheet-close.svg', () => 'close.svg');
jest.mock('../../assets/icons/kakao.svg', () => 'kakao.svg');
jest.mock('../../assets/images/profile-fallback.svg', () => 'fallback.svg');

const feedId = '00000000-0000-4000-8000-000000000002';
const commentId = '10000000-0000-4000-8000-000000000002';
const authorId = '20000000-0000-4000-8000-000000000002';
const prepareDialogs = (): void => {
  document.querySelectorAll('dialog').forEach((dialog): void => {
    Object.defineProperties(dialog, {
      showModal: { value: (): void => dialog.setAttribute('open', '') },
      close: { value: (): void => dialog.removeAttribute('open') },
    });
  });
};

beforeEach((): void => {
  jest.restoreAllMocks();
  localStorage.setItem('harudle.has-completed-oauth', 'true');
});

describe('신고 메뉴의 대상과 소유권', (): void => {
  it.each([
    { menu: '피드 신고', targetType: 'FEED', targetId: feedId },
    { menu: '작성자 신고', targetType: 'USER', targetId: authorId },
    { menu: '댓글 신고', targetType: 'COMMENT', targetId: commentId },
  ] as const)(
    '$menu 메뉴가 선택한 대상을 접수한다',
    async ({ menu, targetType, targetId }): Promise<void> => {
      const user = userEvent.setup();
      const submit = jest.spyOn(reports, 'submitReport').mockResolvedValue({
        id: '30000000-0000-4000-8000-000000000001',
        status: 'RECEIVED',
        duplicate: false,
      });
      if (targetType === 'COMMENT')
        render(
          <FeedCommentOptionsButton
            commentId={commentId}
            authorId={authorId}
            isMine={false}
            onDelete={jest.fn()}
          />,
        );
      else
        render(
          <FeedOptionsButton
            feedId={feedId}
            authorId={authorId}
            isMine={false}
            onDelete={jest.fn()}
          />,
        );
      prepareDialogs();
      await user.click(
        screen.getByRole('button', {
          name: targetType === 'COMMENT' ? '댓글 더보기' : '피드 더보기',
        }),
      );
      expect(
        screen.queryByRole('button', { name: /삭제$/ }),
      ).not.toBeInTheDocument();
      await user.click(screen.getByRole('button', { name: menu }));
      await user.click(screen.getByRole('radio', { name: '스팸·광고' }));
      await user.click(
        screen.getByRole('button', { name: REPORT_COPY.submit }),
      );
      expect(await screen.findByRole('status')).toHaveTextContent(
        REPORT_COPY.received,
      );
      expect(submit).toHaveBeenCalledWith({
        targetType,
        targetId,
        reason: 'SPAM',
        description: '',
      });
    },
  );

  it('댓글 메뉴에서도 작성자를 신고할 수 있다', async (): Promise<void> => {
    const user = userEvent.setup();
    const submit = jest.spyOn(reports, 'submitReport').mockResolvedValue({
      id: '30000000-0000-4000-8000-000000000001',
      status: 'RECEIVED',
      duplicate: false,
    });
    render(
      <FeedCommentOptionsButton
        commentId={commentId}
        authorId={authorId}
        isMine={false}
        onDelete={jest.fn()}
      />,
    );
    prepareDialogs();
    await user.click(screen.getByRole('button', { name: '댓글 더보기' }));
    await user.click(screen.getByRole('button', { name: '작성자 신고' }));
    await user.click(screen.getByRole('radio', { name: '기타' }));
    await user.click(screen.getByRole('button', { name: REPORT_COPY.submit }));
    await screen.findByRole('status');
    expect(submit).toHaveBeenCalledWith({
      targetType: 'USER',
      targetId: authorId,
      reason: 'OTHER',
      description: '',
    });
  });

  it('비로그인 사용자는 신고 입력 대신 기존 로그인 안내를 받는다', async (): Promise<void> => {
    const user = userEvent.setup();
    const submit = jest.spyOn(reports, 'submitReport');
    localStorage.clear();
    render(
      <FeedOptionsButton
        feedId={feedId}
        authorId={authorId}
        isMine={false}
        onDelete={jest.fn()}
      />,
    );
    prepareDialogs();
    await user.click(screen.getByRole('button', { name: '피드 더보기' }));
    await user.click(screen.getByRole('button', { name: '피드 신고' }));
    expect(screen.getByText(REPORT_COPY.loginDescription)).toBeInTheDocument();
    expect(screen.queryByRole('radio')).not.toBeInTheDocument();
    expect(submit).not.toHaveBeenCalled();
  });

  it('본인 피드는 삭제 메뉴를 유지하고 본인 신고를 제공하지 않는다', async (): Promise<void> => {
    const user = userEvent.setup();
    render(
      <FeedOptionsButton
        feedId={feedId}
        authorId={authorId}
        isMine
        onDelete={jest.fn()}
      />,
    );
    prepareDialogs();
    await user.click(screen.getByRole('button', { name: '피드 더보기' }));
    expect(
      screen.getByRole('button', { name: /피드 삭제$/ }),
    ).toBeInTheDocument();
    expect(
      screen.queryByRole('button', { name: '피드 신고' }),
    ).not.toBeInTheDocument();
    expect(
      screen.queryByRole('button', { name: '작성자 신고' }),
    ).not.toBeInTheDocument();
  });

  it('탈퇴한 댓글 작성자는 기본 프로필을 유지하고 댓글 신고만 제공한다', async (): Promise<void> => {
    const user = userEvent.setup();
    render(
      <ul>
        <FeedCommentItem
          comment={{
            id: commentId,
            author: {
              id: null,
              nickname: '탈퇴한 사용자',
              profileImageUrl: null,
            },
            content: '남아 있는 댓글',
            createdAt: '2026-10-11T12:00:00+09:00',
            isMine: false,
          }}
          onDelete={jest.fn()}
        />
      </ul>,
    );
    prepareDialogs();
    expect(screen.getByText('남아 있는 댓글')).toBeInTheDocument();
    expect(screen.getByRole('img', { name: '프로필 이미지' })).toHaveAttribute(
      'src',
      'fallback.svg',
    );
    await user.click(screen.getByRole('button', { name: '댓글 더보기' }));
    expect(
      screen.getByRole('button', { name: '댓글 신고' }),
    ).toBeInTheDocument();
    expect(
      screen.queryByRole('button', { name: '작성자 신고' }),
    ).not.toBeInTheDocument();
  });
});
