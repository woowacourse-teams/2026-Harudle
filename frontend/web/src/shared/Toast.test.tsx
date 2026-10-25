import { describe, expect, it, jest } from '@jest/globals';
import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import Toast from './Toast';

jest.mock('../assets/icons/toast-success.svg', () => 'toast-success.svg');
jest.mock('../assets/icons/toast-error.svg', () => 'toast-error.svg');
jest.mock('../assets/icons/toast-loading.svg', () => 'toast-loading.svg');

describe('토스트의 상태 안내', (): void => {
  it.each([
    ['success', 'status'],
    ['loading', 'status'],
    ['error', 'alert'],
  ] as const)(
    '%s 유형은 메시지와 설명을 하나의 %s 영역으로 제공한다',
    (type, role): void => {
      const { container } = render(
        <Toast
          type={type}
          message="문의 처리 상태 안내"
          description="자세한 내용을 확인해 주세요."
        />,
      );

      const announcement = screen.getByRole(role);
      expect(announcement).toHaveAttribute('aria-atomic', 'true');
      expect(
        within(announcement).getByText('문의 처리 상태 안내'),
      ).toBeInTheDocument();
      expect(
        within(announcement).getByText('자세한 내용을 확인해 주세요.'),
      ).toBeInTheDocument();
      expect(
        container.querySelectorAll('[role="status"], [role="alert"]'),
      ).toHaveLength(1);
    },
  );

  it('동작 버튼은 문구 안내 영역과 구분하고 기존 콜백을 실행한다', async (): Promise<void> => {
    const user = userEvent.setup();
    const onAction = jest.fn<() => void>();
    const onClose = jest.fn<() => void>();
    render(
      <Toast
        type="error"
        message="문의 내용을 복사하지 못했어요."
        action={{ label: '다시 시도', onClick: onAction }}
        onClose={onClose}
      />,
    );

    expect(
      within(screen.getByRole('alert')).queryByRole('button'),
    ).not.toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: '다시 시도' }));
    await user.click(screen.getByRole('button', { name: '×' }));
    expect(onAction).toHaveBeenCalledTimes(1);
    expect(onClose).toHaveBeenCalledTimes(1);
  });
});
