import { delay, http, HttpResponse } from 'msw';
import { findLatestSucceededGuestTrialMockDiary } from './guestTrialHandlers';

// 피드 게스트 체험 실험 전용 mock. 실험을 걷어낼 때 이 파일과 browser.ts 연결만 지운다.
const CLAIMED_DIARY_KEY = 'harudle:mock:guest-feed-trial-claimed:v1';

interface ClaimedDiary {
  readonly id: string;
  readonly diaryDate: string;
  readonly sourceText: string;
  readonly createdAt: string;
  readonly generation: unknown;
  readonly claimedAt: string;
}

const readClaimedDiary = (): ClaimedDiary | null => {
  try {
    const value = sessionStorage.getItem(CLAIMED_DIARY_KEY);
    if (!value) return null;
    const parsed: unknown = JSON.parse(value);
    return typeof parsed === 'object' &&
      parsed !== null &&
      'id' in parsed &&
      typeof parsed.id === 'string' &&
      'diaryDate' in parsed &&
      typeof parsed.diaryDate === 'string' &&
      'sourceText' in parsed &&
      typeof parsed.sourceText === 'string' &&
      'createdAt' in parsed &&
      typeof parsed.createdAt === 'string' &&
      'generation' in parsed &&
      'claimedAt' in parsed &&
      typeof parsed.claimedAt === 'string'
      ? {
          id: parsed.id,
          diaryDate: parsed.diaryDate,
          sourceText: parsed.sourceText,
          createdAt: parsed.createdAt,
          generation: parsed.generation,
          claimedAt: parsed.claimedAt,
        }
      : null;
  } catch {
    return null;
  }
};

const problem = (status: number, code: string, instance: string) => {
  return HttpResponse.json(
    {
      type: `https://api.harudle.example/problems/${code.toLowerCase()}`,
      title: code,
      status,
      detail: '가져올 체험 만화를 찾지 못했습니다.',
      instance,
      code,
    },
    { status, headers: { 'Content-Type': 'application/problem+json' } },
  );
};

export const guestFeedTrialHandlers = [
  http.post('/api/v1/guest/diary-claim', async ({ request }) => {
    await delay(400);
    const instance = '/api/v1/guest/diary-claim';
    if (!request.headers.get('Authorization')) {
      return problem(401, 'UNAUTHORIZED', instance);
    }

    let diary = readClaimedDiary();
    if (!diary) {
      const guestDiary = findLatestSucceededGuestTrialMockDiary();
      if (!guestDiary) {
        return problem(404, 'GUEST_DIARY_CLAIM_NOT_FOUND', instance);
      }
      diary = { ...guestDiary, claimedAt: new Date().toISOString() };
      sessionStorage.setItem(CLAIMED_DIARY_KEY, JSON.stringify(diary));
    }

    return HttpResponse.json(
      {
        diaryId: diary.id,
        diaryDate: diary.diaryDate,
        generationStatus: 'SUCCEEDED',
        claimedAt: diary.claimedAt,
      },
      { headers: { 'Cache-Control': 'no-store' } },
    );
  }),

  // claim한 일기만 회원 상세로 응답하고, 나머지는 기존 mock으로 넘긴다.
  http.get('/api/v1/diaries/:diaryId', async ({ params }) => {
    const claimed = readClaimedDiary();
    if (claimed?.id !== String(params.diaryId)) return undefined;

    await delay(300);
    return HttpResponse.json({
      id: claimed.id,
      publishedFeedId: null,
      diaryDate: claimed.diaryDate,
      sourceText: claimed.sourceText,
      createdAt: claimed.createdAt,
      generation: claimed.generation,
    });
  }),
];
