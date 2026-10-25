import { beforeEach, describe, expect, it, jest } from '@jest/globals';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import {
  getLegalDocument,
  getLegalDocuments,
  type LegalDocument,
  type LegalDocumentsResponse,
} from '../../domain/legal/legalDocuments';
import terms from '../../mocks/legal/terms-2026-10-22.json';
import privacy from '../../mocks/legal/privacy-2026-08-24.json';
import LegalDocumentPage from './LegalDocumentPage';
import { RequestError } from '../../shared/api';

const mockNavigate = jest.fn();
let mockVersion: string | undefined;
jest.mock('react-router', () => ({
  useNavigate: () => mockNavigate,
  useParams: (): { version: string | undefined } => ({ version: mockVersion }),
}));

const mockGetDocuments = jest.fn<typeof getLegalDocuments>();
const mockGetDocument = jest.fn<typeof getLegalDocument>();
jest.mock('../../domain/legal/legalDocuments', () => ({
  getLegalDocuments: (
    ...args: Parameters<typeof getLegalDocuments>
  ): Promise<LegalDocumentsResponse> => mockGetDocuments(...args),
  getLegalDocument: (
    ...args: Parameters<typeof getLegalDocument>
  ): Promise<LegalDocument> => mockGetDocument(...args),
}));
jest.mock('../../assets/icons/back.svg', () => 'back.svg');
jest.mock('../../assets/images/loading-animation.webp', () => 'loading.webp');
const catalog: LegalDocumentsResponse = {
  documents: [
    { ...terms, type: 'TERMS' },
    { ...privacy, type: 'PRIVACY' },
  ],
};
beforeEach((): void => {
  mockVersion = undefined;
  mockNavigate.mockReset();
  mockGetDocuments.mockReset().mockResolvedValue(catalog);
  mockGetDocument.mockReset().mockResolvedValue({ ...terms, type: 'TERMS' });
});
const renderDocument = (path = '/legal/terms'): ReturnType<typeof render> => {
  mockVersion = path.split('/')[3];
  return render(
    <LegalDocumentPage
      type={path.startsWith('/legal/privacy') ? 'PRIVACY' : 'TERMS'}
    />,
  );
};
describe('공개 전문 열람', (): void => {
  it('개인정보 문서의 전문과 시행일·버전을 보여준다', async (): Promise<void> => {
    renderDocument('/legal/privacy');
    expect(
      screen.getByRole('status', { name: '문서를 불러오는 중이에요.' }),
    ).toBeInTheDocument();
    expect(
      await screen.findByRole('heading', { name: privacy.title }),
    ).toBeInTheDocument();
    expect(screen.getByText('2026.08.24')).toBeInTheDocument();
    expect(screen.getByText(privacy.version)).toBeInTheDocument();
    expect(
      screen.getByRole('heading', { name: privacy.sections[0].title }),
    ).toBeInTheDocument();
    expect(screen.getByRole('link', { name: '원문 보기' })).toHaveAttribute(
      'href',
      privacy.sourceUrl,
    );
  });
  it('불러오기 실패 후 같은 화면에서 다시 조회한다', async (): Promise<void> => {
    mockGetDocuments.mockRejectedValueOnce(new Error('장애'));
    const user = userEvent.setup();
    renderDocument();
    expect(await screen.findByRole('alert')).toHaveTextContent(
      '문서를 불러오지 못했어요.',
    );
    await user.click(screen.getByRole('button', { name: '다시 불러오기' }));
    expect(
      await screen.findByRole('heading', { name: terms.title }),
    ).toBeInTheDocument();
    expect(mockGetDocuments).toHaveBeenCalledTimes(2);
    expect(mockGetDocuments.mock.calls[0]?.[0]?.signal?.aborted).toBe(true);
  });
  it('주소에 있는 버전으로 조회하고 본문을 HTML로 실행하지 않는다', async (): Promise<void> => {
    mockGetDocument.mockResolvedValueOnce({
      ...terms,
      type: 'TERMS',
      sections: [{ title: '본문', paragraphs: ['<script>alert(1)</script>'] }],
    });
    renderDocument(`/legal/terms/${terms.version}`);
    expect(
      await screen.findByText('<script>alert(1)</script>'),
    ).toBeInTheDocument();
    expect(document.querySelector('article script')).toBeNull();
    expect(mockGetDocument).toHaveBeenCalledWith('TERMS', terms.version, {
      signal: expect.any(AbortSignal),
    });
    expect(mockGetDocuments).not.toHaveBeenCalled();
  });
  it('없는 버전은 현재 문서로 이동할 수 있게 안내한다', async (): Promise<void> => {
    mockGetDocument.mockRejectedValueOnce(
      new RequestError({
        type: 'about:blank',
        title: 'Not found',
        status: 404,
        detail: '문서를 찾을 수 없어요.',
        instance: '/api/v1/legal/documents/terms/old',
        code: 'LEGAL_DOCUMENT_NOT_FOUND',
      }),
    );
    const user = userEvent.setup();
    renderDocument('/legal/terms/old');
    expect(await screen.findByRole('alert')).toHaveTextContent(
      '문서를 찾을 수 없어요.',
    );
    await user.click(screen.getByRole('button', { name: '현재 문서 보기' }));
    expect(mockNavigate).toHaveBeenCalledWith('/legal/terms', {
      replace: true,
    });
    expect(mockGetDocument).toHaveBeenCalledTimes(1);
  });

  it('화면을 벗어나면 진행 중인 조회를 취소한다', async (): Promise<void> => {
    mockGetDocuments.mockImplementationOnce(
      (): Promise<LegalDocumentsResponse> => new Promise((): void => undefined),
    );
    const { unmount } = renderDocument();
    await waitFor(() => expect(mockGetDocuments).toHaveBeenCalledTimes(1));
    const signal = mockGetDocuments.mock.calls[0]?.[0]?.signal;
    unmount();
    expect(signal?.aborted).toBe(true);
  });
});
