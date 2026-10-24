import { describe, expect, it, jest } from '@jest/globals';
import { act, fireEvent, render, screen } from '@testing-library/react';
import { createRef } from 'react';
import Modal, { type ModalHandle } from './Modal';

describe('모달 닫기 허용 여부', (): void => {
  it('닫기 잠금 중에는 Esc와 배경을 막고 해제하면 기본 동작을 복구한다', (): void => {
    const ref = createRef<ModalHandle>();
    const { rerender } = render(
      <Modal title="신고" ref={ref} dismissible={false}>
        <button type="button">입력</button>
      </Modal>,
    );
    const dialog = document.querySelector('dialog');
    if (!dialog) throw new Error('모달이 없습니다.');
    dialog.setAttribute('open', '');
    expect(screen.getByRole('dialog', { name: '신고', hidden: true })).toBe(
      dialog,
    );
    const close = jest.fn();
    Object.defineProperty(dialog, 'close', { value: close });

    const blockedCancel = new Event('cancel', { cancelable: true });
    fireEvent(dialog, blockedCancel);
    fireEvent.click(dialog);
    expect(blockedCancel.defaultPrevented).toBe(true);
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
});
