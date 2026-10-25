import { beforeEach, describe, expect, it, jest } from '@jest/globals';
import {
  act,
  fireEvent,
  render,
  screen,
  waitFor,
} from '@testing-library/react';
import ContactPage from './ContactPage';
import { CONTACT_COPY, CONTACT_MAILTO_URL } from './copy';

const mockAddToast = jest.fn();
const mockWriteText = jest.fn<(text: string) => Promise<void>>();

jest.mock('react-router', () => ({
  Link: ({
    to,
    children,
    ...props
  }: {
    to: string;
    children: import('react').ReactNode;
  }) => (
    <a href={to} {...props}>
      {children}
    </a>
  ),
}));

jest.mock('../../shared/ToastContext', () => ({
  useToastContext: () => ({ addToast: mockAddToast }),
}));
jest.mock('../../assets/icons/back.svg', () => 'back.svg');
jest.mock('../../assets/icons/content-copy.svg', () => 'copy.svg');

const renderContactPage = (): ReturnType<typeof render> =>
  render(<ContactPage />);

beforeEach((): void => {
  mockAddToast.mockReset();
  mockWriteText.mockReset().mockResolvedValue(undefined);
  Object.defineProperty(navigator, 'clipboard', {
    configurable: true,
    value: { writeText: mockWriteText },
  });
});

describe('공개 문의 화면', (): void => {
  it('주소와 직접 발송 안내를 표시하고 처음 방문해도 홈으로 돌아갈 수 있다', (): void => {
    renderContactPage();

    expect(
      screen.getByRole('heading', { level: 1, name: '문의하기' }),
    ).toBeInTheDocument();
    expect(screen.getByText(CONTACT_COPY.emailAddress)).toBeInTheDocument();
    expect(screen.getByText(CONTACT_COPY.mailNotice)).toBeInTheDocument();
    expect(
      screen.getByRole('link', { name: CONTACT_COPY.backLabel }),
    ).toHaveAttribute('href', '/');
    expect(screen.queryByText(/접수.*완료/)).not.toBeInTheDocument();
    expect(mockAddToast).not.toHaveBeenCalled();
  });

  it('운영팀 주소와 문의 양식만 메일에 미리 채운다', (): void => {
    const mailto = new URL(CONTACT_MAILTO_URL);

    expect(mailto.protocol).toBe('mailto:');
    expect(mailto.pathname).toBe('harudle.official@gmail.com');
    expect([...mailto.searchParams.keys()]).toEqual(['subject', 'body']);
    expect(mailto.searchParams.get('subject')).toBe('[하루들 문의]');
    expect(mailto.searchParams.get('body')).toBe(
      '비밀번호나 일기 원문은 적지 말아 주세요.\n\n문의 종류: 이용 오류 / 계정·로그인 / 기타\n\n문의 내용:\n\n문제 발생 시점 (선택):',
    );
  });

  it('이메일 주소가 복사된 뒤 성공 토스트를 표시한다', async (): Promise<void> => {
    renderContactPage();
    fireEvent.click(
      screen.getByRole('button', { name: CONTACT_COPY.copyAction }),
    );

    await waitFor((): void => {
      expect(mockAddToast).toHaveBeenCalledWith(
        { type: 'success', message: CONTACT_COPY.copySuccess },
        2_000,
      );
    });
    expect(mockWriteText).toHaveBeenCalledTimes(1);
    expect(mockWriteText).toHaveBeenCalledWith(CONTACT_COPY.emailAddress);
  });

  it('복사 중에는 버튼을 잠그고 완료 뒤 다시 복사할 수 있다', async (): Promise<void> => {
    let finishCopy: (() => void) | undefined;
    mockWriteText.mockImplementationOnce(
      (): Promise<void> =>
        new Promise<void>((resolve): void => {
          finishCopy = resolve;
        }),
    );
    renderContactPage();
    fireEvent.click(
      screen.getByRole('button', { name: CONTACT_COPY.copyAction }),
    );
    const copyingButton = screen.getByRole('button', {
      name: CONTACT_COPY.copyPending,
    });
    expect(copyingButton).toBeDisabled();
    fireEvent.click(copyingButton);
    expect(mockWriteText).toHaveBeenCalledTimes(1);
    expect(mockAddToast).not.toHaveBeenCalled();

    await act(async (): Promise<void> => finishCopy?.());
    fireEvent.click(
      screen.getByRole('button', { name: CONTACT_COPY.copyAction }),
    );
    await waitFor((): void => expect(mockWriteText).toHaveBeenCalledTimes(2));
  });

  it('복사 실패 시 직접 복사를 안내하고 같은 화면에서 재시도한다', async (): Promise<void> => {
    mockWriteText.mockRejectedValueOnce(new Error('NotAllowedError'));
    renderContactPage();
    fireEvent.click(
      screen.getByRole('button', { name: CONTACT_COPY.copyAction }),
    );
    await waitFor((): void => {
      expect(mockAddToast).toHaveBeenCalledWith(
        { type: 'error', message: CONTACT_COPY.copyFailed },
        3_000,
      );
    });
    expect(screen.getByText(CONTACT_COPY.emailAddress)).toBeInTheDocument();

    fireEvent.click(
      screen.getByRole('button', { name: CONTACT_COPY.copyAction }),
    );
    await waitFor((): void => {
      expect(mockAddToast).toHaveBeenLastCalledWith(
        { type: 'success', message: CONTACT_COPY.copySuccess },
        2_000,
      );
    });
    expect(mockWriteText).toHaveBeenCalledTimes(2);
  });

  it('클립보드를 지원하지 않으면 주소를 직접 선택해 복사하도록 안내한다', async (): Promise<void> => {
    Object.defineProperty(navigator, 'clipboard', {
      configurable: true,
      value: undefined,
    });
    renderContactPage();
    fireEvent.click(
      screen.getByRole('button', { name: CONTACT_COPY.copyAction }),
    );

    await waitFor((): void => {
      expect(mockAddToast).toHaveBeenCalledWith(
        { type: 'error', message: CONTACT_COPY.copyUnsupported },
        3_000,
      );
    });
    expect(mockWriteText).not.toHaveBeenCalled();
    expect(screen.getByText(CONTACT_COPY.emailAddress)).toBeInTheDocument();
    expect(
      screen.getByRole('button', { name: CONTACT_COPY.copyAction }),
    ).toBeEnabled();
  });

  it('복사 요청 중 화면을 떠나면 결과 토스트를 남기지 않는다', async (): Promise<void> => {
    let finishCopy: (() => void) | undefined;
    mockWriteText.mockImplementationOnce(
      (): Promise<void> =>
        new Promise<void>((resolve): void => {
          finishCopy = resolve;
        }),
    );
    const { unmount } = renderContactPage();
    fireEvent.click(
      screen.getByRole('button', { name: CONTACT_COPY.copyAction }),
    );
    unmount();
    await act(async (): Promise<void> => finishCopy?.());

    expect(mockAddToast).not.toHaveBeenCalled();
  });
});
