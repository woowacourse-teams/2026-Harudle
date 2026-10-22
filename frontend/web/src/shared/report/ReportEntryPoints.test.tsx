import { beforeEach, describe, expect, it, jest } from '@jest/globals';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import * as reports from '../../domain/reports/reportSubmission';
import ReportOptionsButton from './ReportOptionsButton';
import { REPORT_COPY } from './copy';

jest.mock('react-router', () => ({ useNavigate: () => jest.fn() }));
jest.mock('../../assets/icons/feed-more.svg', () => 'more.svg');
jest.mock('../../assets/icons/bottom-sheet-close.svg', () => 'close.svg');
jest.mock('../../assets/icons/kakao.svg', () => 'kakao.svg');

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

describe('타인 콘텐츠의 신고 진입', (): void => {
  it.each([
    {
      contentType: 'FEED',
      contentId: feedId,
      menu: '피드 신고',
      targetType: 'FEED',
      targetId: feedId,
    },
    {
      contentType: 'FEED',
      contentId: feedId,
      menu: '작성자 신고',
      targetType: 'USER',
      targetId: authorId,
    },
    {
      contentType: 'COMMENT',
      contentId: commentId,
      menu: '댓글 신고',
      targetType: 'COMMENT',
      targetId: commentId,
    },
    {
      contentType: 'COMMENT',
      contentId: commentId,
      menu: '작성자 신고',
      targetType: 'USER',
      targetId: authorId,
    },
  ] as const)(
    '$contentType의 $menu가 메뉴를 닫고 선택한 대상을 접수한다',
    async ({
      contentType,
      contentId,
      menu,
      targetType,
      targetId,
    }): Promise<void> => {
      const user = userEvent.setup();
      const submit = jest.spyOn(reports, 'submitReport').mockResolvedValue({
        id: '30000000-0000-4000-8000-000000000001',
        status: 'RECEIVED',
        duplicate: false,
      });
      render(
        <ReportOptionsButton
          target={{ targetType: contentType, targetId: contentId }}
          authorId={authorId}
        />,
      );
      prepareDialogs();
      await user.click(
        screen.getByRole('button', {
          name: contentType === 'FEED' ? '피드 더보기' : '댓글 더보기',
        }),
      );
      expect(
        screen.queryByRole('button', { name: /삭제$/ }),
      ).not.toBeInTheDocument();
      await user.click(screen.getByRole('button', { name: menu }));
      expect(
        screen.queryByRole('heading', {
          name: REPORT_COPY.menuTitle,
        }),
      ).not.toBeInTheDocument();
      expect(screen.getByRole('heading', { name: menu })).toBeInTheDocument();
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

  it.each(['FEED', 'COMMENT'] as const)(
    '비로그인 사용자의 %s 신고는 메뉴를 닫고 기존 로그인 안내를 연다',
    async (targetType): Promise<void> => {
      const user = userEvent.setup();
      const submit = jest.spyOn(reports, 'submitReport');
      localStorage.clear();
      render(
        <ReportOptionsButton
          target={{
            targetType,
            targetId: targetType === 'FEED' ? feedId : commentId,
          }}
          authorId={authorId}
        />,
      );
      prepareDialogs();
      await user.click(
        screen.getByRole('button', {
          name: targetType === 'FEED' ? '피드 더보기' : '댓글 더보기',
        }),
      );
      await user.click(
        screen.getByRole('button', {
          name: targetType === 'FEED' ? '피드 신고' : '댓글 신고',
        }),
      );
      expect(
        screen.queryByRole('heading', {
          name: REPORT_COPY.menuTitle,
        }),
      ).not.toBeInTheDocument();
      expect(
        screen.getByText(REPORT_COPY.loginDescription),
      ).toBeInTheDocument();
      expect(screen.queryByRole('radio')).not.toBeInTheDocument();
      expect(submit).not.toHaveBeenCalled();
    },
  );

  it('탈퇴한 작성자의 댓글은 댓글 신고만 제공한다', async (): Promise<void> => {
    const user = userEvent.setup();
    render(
      <ReportOptionsButton
        target={{ targetType: 'COMMENT', targetId: commentId }}
        authorId={null}
      />,
    );
    prepareDialogs();
    await user.click(screen.getByRole('button', { name: '댓글 더보기' }));
    expect(
      screen.getByRole('button', { name: '댓글 신고' }),
    ).toBeInTheDocument();
    expect(
      screen.queryByRole('button', { name: '작성자 신고' }),
    ).not.toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: '댓글 신고' }));
    expect(
      screen.getByRole('heading', { name: '댓글 신고' }),
    ).toBeInTheDocument();
  });
});
