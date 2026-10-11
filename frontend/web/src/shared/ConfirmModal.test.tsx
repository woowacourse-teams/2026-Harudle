import { describe, expect, it, jest } from '@jest/globals';
import { act, render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { createRef, type RefObject } from 'react';
import ConfirmModal from './ConfirmModal';
import type { ModalHandle } from './Modal';

interface ConfirmModalFixture {
  modalRef: RefObject<ModalHandle | null>;
  dialog: HTMLElement;
  user: ReturnType<typeof userEvent.setup>;
}

const renderOpenedModal = (onConfirm: () => void): ConfirmModalFixture => {
  const modalRef = createRef<ModalHandle>();
  const user = userEvent.setup();

  render(
    <ConfirmModal
      title="일기를 삭제할까요?"
      description="삭제한 일기는 복구할 수 없어요."
      confirmLabel="삭제"
      ref={modalRef}
      onConfirm={onConfirm}
    />,
  );

  const dialog = screen.getByRole('dialog', { hidden: true });

  // JSDOM에 없는 dialog API를 이 요소에만 보완한다.
  Object.defineProperties(dialog, {
    showModal: {
      value: (): void => dialog.setAttribute('open', ''),
    },
    close: {
      value: (): void => dialog.removeAttribute('open'),
    },
  });

  act((): void => modalRef.current?.open());
  expect(dialog).toHaveAttribute('open');

  return { modalRef, dialog, user };
};

describe('ConfirmModal', (): void => {
  it('취소하면 모달을 닫고 확인 콜백은 실행하지 않는다', async (): Promise<void> => {
    const onConfirm = jest.fn<() => void>();
    const { dialog, user } = renderOpenedModal(onConfirm);

    await user.click(screen.getByRole('button', { name: '취소' }));

    expect(dialog).not.toHaveAttribute('open');
    expect(onConfirm).not.toHaveBeenCalled();
  });

  it('확인하면 콜백을 한 번 실행하고 모달은 열린 상태로 유지한다', async (): Promise<void> => {
    const onConfirm = jest.fn<() => void>();
    const { dialog, user } = renderOpenedModal(onConfirm);

    await user.click(screen.getByRole('button', { name: '삭제' }));

    expect(onConfirm).toHaveBeenCalledTimes(1);
    expect(dialog).toHaveAttribute('open');
  });

  it('호출자가 확인 처리 후 ref로 모달을 닫을 수 있다', async (): Promise<void> => {
    const { modalRef, dialog, user } = renderOpenedModal((): void => {
      modalRef.current?.close();
    });

    await user.click(screen.getByRole('button', { name: '삭제' }));

    expect(dialog).not.toHaveAttribute('open');
  });
});
