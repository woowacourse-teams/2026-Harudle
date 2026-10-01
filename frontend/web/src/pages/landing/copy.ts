import friendsBirthdayImage from './assets/friends-birthday.png';
import workDifferentPagesImage from './assets/work-different-pages.png';
import schoolPresentationImage from './assets/school-presentation.png';
import slackToothpasteImage from './assets/slack-toothpaste.png';
import slackFavoriteImage from './assets/slack-favorite.png';
import slackSongQuizImage from './assets/slack-song-quiz.png';
import slackChorokImage from './assets/slack-chorok.png';
import slackIqImage from './assets/slack-iq.png';
import slackIhyunImage from './assets/slack-ihyun.png';

export const LANDING_COPY = {
  hero: {
    titleLines: ['우리끼리 통하는', '네컷만화'],
    description:
      '함께 있었던 순간을 적으면\n하루들이 네컷만화로 만들어 드려요.',
    action: '네컷만화 만들어 보기',
    trialNotice: '로그인 없이 한 번 만들어 볼 수 있어요.',
    previewCaption: '우리끼리 두고두고 꺼내 볼 순간',
    scrollHintLabel: '아래 예시 보기',
  },
  examples: {
    title: '사진은 없어도,\n내용만 있으면 돼요',
    choiceHint: '눌러서 다른 이야기도 보세요',
  },
  share: {
    title: '생성한 만화를\n공유해보세요',
    communityTitle: '우리끼리는 이미\n이렇게 나누고 있어요',
    communityNotice: '우아한테크코스 교육생들이 슬랙에 올린 네컷만화예요.',
    slackLabel: '우아한테크코스의 슬랙 공유 사례',
    slackChannelName: '그림일기-공유',
    // 사용자가 전달한 대표 수치. 집계 기준은 확인 후 확정한다.
    usageStats: [
      { value: 1300, prefix: '', suffix: '장+', label: '만들어진 네컷만화' },
      { value: 160, prefix: '', suffix: '명+', label: '함께하는 사람' },
    ],
    sceneLabel: '공유 장면 예시',
    sceneTitle: '생일 작전방 · 3',
    sceneMessage: '어제 서프라이즈 사진 찍은 거 있어?',
    sceneReply: '정신없어서 못 찍었어ㅋㅋ',
    sceneFollowUp: '대신 네컷으로 남겨봤어!',
    sceneReaction: 'ㅋㅋ 기사님도 같이 놀랐잖아',
    imageReactions: [
      { emoji: '😂', label: '웃으며 눈물', count: 1 },
      { emoji: '😆', label: '크게 웃음', count: 1 },
    ],
    senderName: '나',
    recipientName: '친구',
    dateLabel: '어제',
    inputPlaceholder: '메시지 입력',
  },
  final: {
    title: '최근에 같이 웃었던\n순간이 있나요?',
    loginPrompt: '이미 만들어 봤나요?',
    loginAction: '카카오로 이어서 만들기 →',
  },
  footer: {
    brand: '하루들',
    tagline: '우리끼리 통하는 네컷만화',
    copyright: '© 2026 Harudle',
    links: [
      { label: '개인정보처리방침', href: 'https://harudle.notion.site/' },
      { label: '문의하기', href: 'mailto:harudle.official@gmail.com' },
      {
        label: 'Instagram',
        href: 'https://www.instagram.com/harudle.official/',
      },
    ],
  },
} as const;

export const LANDING_SLACK_STORY = {
  title: '직업병과 치약',
  sender: '이산',
  time: '오후 1:27',
  imageUrl: slackToothpasteImage,
  reactions: [
    { kind: 'text', value: 'ㅋㅋ', label: '웃음', count: 15 },
    { kind: 'text', value: '와우', label: '와우', count: 6 },
    { kind: 'text', value: '바보', label: '바보', count: 3 },
    { kind: 'emoji', value: '🚨', label: '경광등', count: 2 },
  ],
  replyCount: 7,
  // 답글 원문은 유지하고 사용자가 지정한 닉네임과 프로필을 표시한다.
  replies: [
    {
      sender: '초록',
      time: '오후 1:30',
      profileImageUrl: slackChorokImage,
      message: 'localhost:2080',
    },
  ],
  relatedPosts: [
    {
      title: '아련한 노래 퀴즈',
      sender: '이현',
      profileImageUrl: slackIhyunImage,
      reactions: [
        { emoji: '🎧', label: '헤드폰', count: null },
        { emoji: '👏', label: '박수', count: null },
      ],
      time: '오전 12:06',
      imageUrl: slackSongQuizImage,
      replyCount: 9,
    },
    {
      title: '내 최애를 소개합니다',
      sender: '아이큐',
      profileImageUrl: slackIqImage,
      reactions: [{ emoji: '✌️', label: '브이', count: 13 }],
      time: '오후 2:15',
      imageUrl: slackFavoriteImage,
      replyCount: 3,
    },
  ],
} as const;

export interface LandingExample {
  readonly id: 'friends' | 'work' | 'school';
  readonly label: string;
  readonly title: string;
  readonly content: string;
  readonly imageUrl: string;
}

export const LANDING_EXAMPLES: readonly LandingExample[] = [
  {
    id: 'friends',
    label: '친구와',
    title: '실패한 생일 서프라이즈',
    content:
      '친구 생일에 숨어 있다가 축하를 외쳤는데, 들어온 건 배달 기사님이었다. 진짜 주인공이 왔을 땐 다 같이 웃었다.',
    imageUrl: friendsBirthdayImage,
  },
  {
    id: 'work',
    label: '동료와',
    title: '서로 다른 페이지',
    content:
      '동료와 한참 말이 안 통해 화면을 봤더니, 나는 첫 페이지를 동료는 마지막 페이지를 보고 있었다. 둘 다 빵 터졌다.',
    imageUrl: workDifferentPagesImage,
  },
  {
    id: 'school',
    label: '학교에서',
    title: '발표 5분 전 업데이트',
    content:
      '발표 직전에 노트북 업데이트가 시작됐다. 결국 폰으로 발표했는데, 끝나자마자 업데이트가 완료됐다.',
    imageUrl: schoolPresentationImage,
  },
] as const;
