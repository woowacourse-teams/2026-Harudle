import { describe, expect, it, jest } from '@jest/globals';
import { act, fireEvent, render, screen } from '@testing-library/react';
import { createRef } from 'react';
import Modal, { type ModalHandle } from './Modal';

describe('모달 닫기 허용 여부', (): void => {
  it('닫기 잠금 중에는 Esc와 배경 클릭을 막고 해제하면 기본 동작을 복구한다', (): void => {
    const ref = createRef<ModalHandle>();
    const { rerender } = render(
      <Modal title="신고" ref={ref} dismissible={false}>
        <button type="button">입력</button>
      </Modal>,
    );
    const dialog = document.querySelector('dialog');
    if (!dialog) throw new Error('모달이 없습니다.');
    const close = jest.fn();
    Object.defineProperty(dialog, 'close', { value: close });

    const blockedCancel = new Event('cancel', { cancelable: true });
    const blockedEscape = new KeyboardEvent('keydown', {
      key: 'Escape',
      bubbles: true,
      cancelable: true,
    });
    fireEvent(dialog, blockedCancel);
    fireEvent(dialog, blockedEscape);
    fireEvent.click(dialog);
    expect(blockedCancel.defaultPrevented).toBe(true);
    expect(blockedEscape.defaultPrevented).toBe(true);
    expect(close).not.toHaveBeenCalled();

    rerender(
      <Modal title="신고" ref={ref}>
        <button type="button">입력</button>
      </Modal>,
    );
    const allowedCancel = new Event('cancel', { cancelable: true });
    fireEvent(dialog, allowedCancel);
    expect(allowedCancel.defaultPrevented).toBe(false);
    fireEvent.click(screen.getByRole('button', { name: '입력', hidden: true }));
    expect(close).not.toHaveBeenCalled();
    fireEvent.click(dialog);
    act((): void => ref.current?.close());
    expect(close).toHaveBeenCalledTimes(2);
  });

  it('닫기 잠금 중에도 호출자가 처리를 마치면 모달을 닫을 수 있다', (): void => {
    const ref = createRef<ModalHandle>();
    render(
      <Modal title="신고" ref={ref} dismissible={false}>
        처리 중
      </Modal>,
    );
    const dialog = document.querySelector('dialog');
    if (!dialog) throw new Error('모달이 없습니다.');
    const showModal = jest.fn((): void => dialog.setAttribute('open', ''));
    Object.defineProperties(dialog, {
      showModal: { value: showModal },
      close: {
        value: (): void => {
          dialog.removeAttribute('open');
          fireEvent(dialog, new Event('close'));
        },
      },
    });

    act((): void => ref.current?.open());
    expect(dialog).toHaveAttribute('open');
    act((): void => ref.current?.close());

    expect(dialog).not.toHaveAttribute('open');
    expect(showModal).toHaveBeenCalledTimes(1);
  });

  it('닫기 잠금 중 호출자 요청 없이 닫히면 모달을 다시 연다', (): void => {
    render(
      <Modal title="신고" dismissible={false} ref={createRef<ModalHandle>()}>
        처리 중
      </Modal>,
    );
    const dialog = document.querySelector('dialog');
    if (!dialog) throw new Error('모달이 없습니다.');
    const showModal = jest.fn((): void => dialog.setAttribute('open', ''));
    Object.defineProperty(dialog, 'showModal', { value: showModal });

    fireEvent(dialog, new Event('close'));

    expect(dialog).toHaveAttribute('open');
    expect(showModal).toHaveBeenCalledTimes(1);
  });
});
