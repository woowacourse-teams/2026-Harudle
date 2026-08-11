import { http, HttpResponse } from 'msw';

const diaryThumbnailUrl = new URL(
  '../assets/images/diary-four-panel.png',
  import.meta.url,
).href;

const augustDiaries = [
  { date: '2026-08-12', title: '비가 와도, 나는 괜찮았다.' },
  { date: '2026-08-11', title: '회사에서 칭찬받은 날!' },
  { date: '2026-08-10', title: '오랜만에 친구들을 만났다.' },
  { date: '2026-08-09', title: '새로운 아이디어가 떠올랐다.' },
  { date: '2026-08-08', title: '아무것도 하지 않은 행복' },
  { date: '2026-08-07', title: '주말엔 영화와 팝콘!' },
].map(({ date, title }) => ({
  date,
  exist: true,
  title,
  thumbnailUrl: diaryThumbnailUrl,
}));

export const handlers = [
  http.get('/api/v1/diaries', ({ request }) => {
    const url = new URL(request.url);
    const year = Number(url.searchParams.get('year'));
    const month = Number(url.searchParams.get('month'));

    return HttpResponse.json({
      year,
      month,
      days: year === 2026 && month === 8 ? augustDiaries : [],
    });
  }),

  http.get('/api/v1/me/generation-usage', () => {
    return HttpResponse.json({
      usageDate: '2026-08-11',
      usedCount: 0,
      limitCount: 3,
      remainingCount: 3,
    });
  }),
];
