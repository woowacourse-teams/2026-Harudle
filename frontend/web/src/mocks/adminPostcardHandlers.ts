import { delay, http, HttpResponse } from 'msw';
import diaryImage from '../assets/images/diary-four-panel.webp';
import { adminGenerations } from './adminModerationHandlers';

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
      const generation = adminGenerations.find(
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
