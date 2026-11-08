import { describe, expect, it } from '@jest/globals';
import { isNotificationsResponse } from './notifications';

const item = {
  id: 'notification-1',
  type: 'LIKE',
  feedId: 'feed-1',
  actor: {
    id: 'actor-1',
    nickname: '네오',
    profileImageUrl: 'https://example.com/profile.webp',
    profileImageUrlExpiresAt: null,
  },
  message: '네오님이 좋아요를 눌렀습니다.',
  createdAt: '2026-10-29T00:00:00Z',
  read: false,
};
const page = {
  items: [item],
  hasNext: false,
  nextCursor: null,
  unreadCount: 1,
};

describe('알림 응답 경계 검증', (): void => {
  it('정상 응답과 탈퇴한 행동자의 null 프로필을 허용한다', (): void => {
    expect(isNotificationsResponse(page)).toBe(true);
    expect(
      isNotificationsResponse({
        ...page,
        items: [{ ...item, type: 'COMMENT', actor: null }],
      }),
    ).toBe(true);
    expect(
      isNotificationsResponse({ ...page, items: [], unreadCount: 0 }),
    ).toBe(true);
  });

  it.each([
    null,
    {},
    { ...page, unreadCount: -1 },
    { ...page, unreadCount: 0.5 },
    { ...page, unreadCount: NaN },
    { ...page, unreadCount: '1' },
    { ...page, hasNext: true },
    { ...page, nextCursor: 'unexpected' },
    { ...page, hasNext: true, nextCursor: '' },
    { ...page, items: [{ ...item, type: 'PUSH' }] },
    { ...page, items: [{ ...item, read: 'false' }] },
    { ...page, items: [{ ...item, createdAt: 'invalid' }] },
    { ...page, items: [{ ...item, actor: {} }] },
    {
      ...page,
      items: [
        {
          ...item,
          actor: { ...item.actor, profileImageUrlExpiresAt: 'invalid' },
        },
      ],
    },
  ])(
    '잘못된 종류·프로필·날짜·미읽음 수·페이지 정보를 거부한다: %j',
    (value): void => {
      expect(isNotificationsResponse(value)).toBe(false);
    },
  );
});
