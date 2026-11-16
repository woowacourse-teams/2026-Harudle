import friendsBirthdayImage from './assets/friends-birthday.png';
import workDifferentPagesImage from './assets/work-different-pages.png';
import schoolPresentationImage from './assets/school-presentation.png';
import slackToothpasteImage from './assets/slack-toothpaste.png';
import slackFavoriteImage from './assets/slack-favorite.png';
import slackSongQuizImage from './assets/slack-song-quiz.png';
import slackChorokImage from './assets/slack-chorok.png';
import slackIqImage from './assets/slack-iq.png';
import slackIhyunImage from './assets/slack-ihyun.png';
import teamChorokImage from './assets/team-chorok.webp';
import teamIqImage from './assets/team-iq.webp';
import teamKaemoImage from './assets/team-kaemo.webp';
import teamIhyunImage from './assets/team-ihyun.webp';
import teamIsanImage from './assets/team-isan.webp';

export const LANDING_CONTACT_EMAIL = 'official@harudle.com';

export const LANDING_COPY = {
  hero: {
    titleLines: ['우리끼리 통하는', '네컷만화'],
    description:
      '다른 사람들의 네컷만화를 구경하고,\n내 이야기도 만화로 만들어 보세요.',
    action: '직접 만들어 보기',
    feedAction: '피드 보러 가기',
    feedNotice: '로그인 없이 바로 구경할 수 있어요.',
    returnNotice: '이 화면으로 돌아오려면 뒤로가기를 눌러주세요.',
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
      { value: 2000, prefix: '', suffix: '장+', label: '만들어진 네컷만화' },
      { value: 170, prefix: '', suffix: '명+', label: '함께하는 사람' },
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
  feed: {
    title: '피드에서\n네컷만화를 구경해요',
    description: '지금 인기있는 네컷만화 두 개를 먼저 만나보세요.',
    action: '피드 둘러보기',
    loading: '네컷만화를 불러오고 있어요',
    error: '네컷만화 미리보기를 불러오지 못했어요.',
    empty: '아직 공개 피드에 올라온 네컷만화가 없어요.',
    retry: '다시 불러오기',
  },
  final: {
    title: '최근에 같이 웃었던\n순간이 있나요?',
    loginPrompt: '이미 만들어 봤나요?',
    loginAction: '로그인하고 이어서 만들기',
  },
  footer: {
    brand: '하루들',
    tagline: '우리끼리 통하는 네컷만화',
    copyright: '© 2026 Harudle',
    links: [
      { label: '이용약관', href: '/legal/terms' },
      { label: '개인정보처리방침', href: '/legal/privacy' },
      { label: '문의하기', href: `mailto:${LANDING_CONTACT_EMAIL}` },
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

export interface LandingJourneyStep {
  readonly period: string;
  readonly title: string;
  readonly description: string;
  readonly metric: { readonly value: string; readonly label: string };
}

// D2SF 소개 자료의 검증 기록에서 공개해도 되는 결과만 골라 요약한다.
export const LANDING_JOURNEY = {
  title: '작은 실험부터\n하나씩 확인했어요',
  description:
    '가설을 세우고, 사용자 반응을 기록하고,\n그 결과로 하루들을 바꿔 왔어요.',
  steps: [
    {
      period: '첫 실험',
      title: '손으로 먼저 만들어 봤어요',
      description:
        '서비스를 만들기 전, 구글폼으로 16명의 이야기를 받아 네컷만화를 직접 만들어 24시간 안에 전달했어요.',
      metric: { value: '14/16명', label: '다른 이야기도 만들고 싶다고 답함' },
    },
    {
      period: '프로토타입',
      title: '직접 써 보게 했어요',
      description:
        'Gemini Canvas로 만든 프로토타입을 제공했어요. 설문에 응답한 12명 모두 이야기를 입력해 결과를 확인했어요.',
      metric: { value: '11/12명', label: '다시 쓰고 싶다고 답함' },
    },
    {
      period: '9월',
      title: '기록보다 공유였어요',
      description:
        '사용자 인터뷰에서 "공유할 생각이 없으면 만들러 오지 않는다"는 답을 들었어요. 그래서 핵심 가치를 공유와 소통으로 다시 정했어요.',
      metric: { value: '36.3%', label: '만든 만화를 슬랙에 공유한 비율' },
    },
    {
      period: '9월',
      title: '함께 만들 주제를 열었어요',
      description:
        '네 가지 주제로 공유 이벤트를 열어, 구체적이고 누구나 공감할 주제가 만화 제작을 돕는다는 것을 확인했어요.',
      metric: { value: '2.8배', label: '이벤트 기간 하루 평균 생성량' },
    },
    {
      period: '10월',
      title: '공유를 서비스 안으로 옮겼어요',
      description:
        '슬랙에서 나누던 만화를 하루들 피드에서 보고 반응하도록 바꿨어요. 이벤트가 끝난 뒤에도 더 오래 머물렀어요.',
      metric: { value: '4.4분 → 6.5분', label: '방문당 평균 체류시간' },
    },
    {
      period: '10월 이후',
      title: '다른 부트캠프로 넓혔어요',
      description:
        '소프트웨어 마에스트로, SSAFY, 크래프톤 정글 등 다른 부트캠프에도 알렸어요. 이제 구경하던 관심이 첫 만화로 이어지는지 실험하고 있어요.',
      metric: {
        value: '59.9%',
        label: '피드에서 카테고리를 바꿔 구경한 방문자',
      },
    },
  ] satisfies readonly LandingJourneyStep[],
} as const;

export interface LandingTeamMember {
  readonly nickname: string;
  readonly name: string;
  readonly githubId: string;
  readonly imageUrl: string;
}

export const LANDING_TEAM = {
  title: '하루들을\n만드는 사람들',
  description:
    '우아한테크코스 8기에서 만난 다섯 명이\n하루들을 함께 만들고 운영하고 있어요.',
  repositoryLabel: '하루들 GitHub 저장소',
  repositoryUrl: 'https://github.com/woowacourse-teams/2026-Harudle',
  members: [
    {
      nickname: '초록',
      name: '이재헌',
      githubId: '2Jaeheon',
      imageUrl: teamChorokImage,
    },
    {
      nickname: '아이큐',
      name: '한동희',
      githubId: 'e9ua1',
      imageUrl: teamIqImage,
    },
    {
      nickname: '캐모',
      name: '김제신',
      githubId: 'jason0904',
      imageUrl: teamKaemoImage,
    },
    {
      nickname: '이현',
      name: '정연준',
      githubId: 'jebiyeon02',
      imageUrl: teamIhyunImage,
    },
    {
      nickname: '이산',
      name: '이채린',
      githubId: 'Rix01',
      imageUrl: teamIsanImage,
    },
  ] satisfies readonly LandingTeamMember[],
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
