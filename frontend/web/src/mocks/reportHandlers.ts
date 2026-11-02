import { delay, http, HttpResponse } from 'msw';
import {
  isReportSubmission,
  type ReportReceipt,
} from '../domain/reports/reportSubmission';

const receipts = new Map<string, ReportReceipt>();
let sequence = 0;

export const resetReportMockState = (): void => {
  receipts.clear();
  sequence = 0;
};

const problem = (status: number, code: string, detail: string): Response =>
  HttpResponse.json(
    {
      type: 'about:blank',
      title: '신고 접수 실패',
      status,
      code,
      detail,
      instance: '/api/v1/reports',
    },
    { status },
  );

export const reportHandlers = [
  http.post('/api/v1/reports', async ({ request }): Promise<Response> => {
    if (request.headers.get('Authorization') !== 'Bearer mock-access-token') {
      return problem(401, 'UNAUTHORIZED', '로그인이 필요합니다.');
    }
    if (request.headers.get('X-XSRF-TOKEN') !== 'mock-csrf-token') {
      return problem(403, 'INVALID_CSRF_TOKEN', '다시 시도해 주세요.');
    }
    const data: unknown = await request.json().catch((): null => null);
    if (!isReportSubmission(data))
      return problem(400, 'INVALID_REPORT', '신고 내용을 확인해 주세요.');
    await delay(300);
    const key = `${data.targetType}:${data.targetId}`;
    const existing = receipts.get(key);
    if (existing) return HttpResponse.json({ ...existing, duplicate: true });
    sequence += 1;
    const receipt: ReportReceipt = {
      id: `00000000-0000-4000-8000-${String(sequence).padStart(12, '0')}`,
      status: 'RECEIVED',
      duplicate: false,
    };
    receipts.set(key, receipt);
    return HttpResponse.json(receipt, { status: 201 });
  }),
];
