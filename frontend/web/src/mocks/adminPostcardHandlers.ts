import { delay, http, HttpResponse } from 'msw';
import diaryImage from '../assets/images/diary-four-panel.webp';

// 관리자 전체 생성 목록의 예시와 엽서 인쇄용 이미지 주소. 실제 서버 없이 엽서 미리보기 흐름을 확인한다.
export const ADMIN_GENERATION_SAMPLES = [
  {
    id: '6a2fbcda-943d-4014-a8e3-246377482e00',
    user: { id: '00000000-0000-4000-8000-000000000201', name: '레오' },
    requestedAt: '2026-11-17T11:52:10Z',
    status: 'SUCCEEDED',
    completedAt: '2026-11-17T11:52:41Z',
    errorCode: null,
  },
  {
    id: '71dc2282-72c5-4c45-8f81-018afbc67ad8',
    user: { id: '00000000-0000-4000-8000-000000000202', name: '쿠다' },
    requestedAt: '2026-11-17T11:49:33Z',
    status: 'FAILED',
    completedAt: '2026-11-17T11:50:20Z',
    errorCode: 'IMAGE_GENERATION_FAILED',
  },
  {
    id: '339fa1fd-8ea7-4830-93b7-e16672e35086',
    user: { id: '00000000-0000-4000-8000-000000000203', name: '이산' },
    requestedAt: '2026-11-17T11:45:02Z',
    status: 'SUCCEEDED',
    completedAt: '2026-11-17T11:45:35Z',
    errorCode: null,
  },
] as const;

const notFound = (): Response =>
  HttpResponse.json(
    {
      type: 'about:blank',
      title: 'Not Found',
      status: 404,
      code: 'NOT_FOUND',
      detail: '엽서로 만들 수 있는 생성 결과가 아닙니다.',
    },
    { status: 404, headers: { 'Content-Type': 'application/problem+json' } },
  );

export const adminPostcardHandlers = [
  http.get(
    '/api/v1/admin/generations/:generationId/postcard-image',
    async ({ request, params }): Promise<Response> => {
      if (request.headers.get('Authorization') !== 'Bearer mock-access-token') {
        return HttpResponse.json(
          { title: 'Unauthorized', status: 401, code: 'UNAUTHORIZED' },
          { status: 401 },
        );
      }
      await delay(400);
      const generation = ADMIN_GENERATION_SAMPLES.find(
        ({ id }) => id === params.generationId,
      );
      if (!generation || generation.status !== 'SUCCEEDED') return notFound();
      return HttpResponse.json({
        imageUrl: new URL(diaryImage, window.location.origin).toString(),
        title: '콘티는 천사다',
      });
    },
  ),
];
