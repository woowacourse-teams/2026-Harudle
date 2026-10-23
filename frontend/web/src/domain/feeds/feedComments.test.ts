import { describe, expect, it } from '@jest/globals';
import { isFeedComment, isFeedCommentsResponse } from './feedComments';
import { isValidCommentContent } from './feedCommentCreate';

const comment = {
  id: 'comment-1',
  author: { id: 'user-1', nickname: '네오', profileImageUrl: '/profile.svg' },
  content: '<script>alert(1)</script>',
  createdAt: '2026-10-11T12:00:00+09:00',
  isMine: false,
};

describe('댓글 입력과 외부 응답 경계', (): void => {
  it('앞뒤 공백 제거와 Unicode code point 기준으로 1~100자를 허용한다', (): void => {
    expect(isValidCommentContent('   ')).toBe(false);
    expect(isValidCommentContent(' 가 ')).toBe(true);
    expect(isValidCommentContent(' 😀 '.trim().repeat(100))).toBe(true);
    expect(isValidCommentContent('😀'.repeat(101))).toBe(false);
  });
  it('댓글의 작성자·시각·소유권을 검증하고 일반 텍스트를 그대로 허용한다', (): void => {
    expect(isFeedComment(comment)).toBe(true);
    for (const value of [
      null,
      {},
      { ...comment, author: {} },
      { ...comment, createdAt: 'invalid' },
      { ...comment, isMine: 'true' },
      { ...comment, content: 12 },
    ])
      expect(isFeedComment(value)).toBe(false);
  });
  it('탈퇴한 작성자의 nullable 프로필을 허용하되 잘못된 ID 타입은 거절한다', (): void => {
    expect(
      isFeedComment({
        ...comment,
        author: { id: null, nickname: '탈퇴한 사용자', profileImageUrl: null },
      }),
    ).toBe(true);
    expect(
      isFeedComment({ ...comment, author: { ...comment.author, id: 12 } }),
    ).toBe(false);
  });
  it('다음 페이지 유무와 커서의 관계를 검증한다', (): void => {
    expect(
      isFeedCommentsResponse({
        items: [comment],
        nextCursor: null,
        hasNext: false,
      }),
    ).toBe(true);
    expect(
      isFeedCommentsResponse({
        items: [comment],
        nextCursor: 'cursor',
        hasNext: true,
      }),
    ).toBe(true);
    expect(
      isFeedCommentsResponse({
        items: [comment],
        nextCursor: null,
        hasNext: true,
      }),
    ).toBe(false);
    expect(
      isFeedCommentsResponse({
        items: [comment],
        nextCursor: 'cursor',
        hasNext: false,
      }),
    ).toBe(false);
    expect(
      isFeedCommentsResponse({ items: [{}], nextCursor: null, hasNext: false }),
    ).toBe(false);
  });
});

it('탈퇴 댓글의 nullable 작성자 정보도 받아들인다', (): void => {
  expect(
    isFeedCommentsResponse({
      items: [
        {
          id: 'comment-1',
          author: { id: null, nickname: '탈퇴한 회원', profileImageUrl: null },
          content: '남겨진 댓글',
          createdAt: '2026-10-22T00:00:00Z',
          isMine: false,
        },
      ],
      nextCursor: null,
      hasNext: false,
    }),
  ).toBe(true);
});
