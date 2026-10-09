import { css } from '@emotion/react';
import {
  useImperativeHandle,
  useRef,
  type ReactElement,
  type Ref,
} from 'react';
import Modal, { type ModalHandle } from './Modal';
import { theme } from '../styles/theme';

interface ConfirmModalProps {
  title: string;
  description: string;
  confirmLabel: string;
  ref: Ref<ModalHandle>;
  onConfirm: () => void;
}

const ConfirmModal = ({
  title,
  description,
  confirmLabel,
  ref,
  onConfirm,
}: ConfirmModalProps): ReactElement => {
  const modalRef = useRef<ModalHandle>(null);

  useImperativeHandle(ref, (): ModalHandle => {
    return {
      open: (): void => modalRef.current?.open(),
      close: (): void => modalRef.current?.close(),
    };
  }, []);

  return (
    <Modal
      title={title}
      ref={modalRef}
      footer={
        <>
          <button
            type="button"
            css={cancelButtonStyle}
            onClick={(): void => modalRef.current?.close()}
          >
            취소
          </button>
          <button type="button" css={confirmButtonStyle} onClick={onConfirm}>
            {confirmLabel}
          </button>
        </>
      }
    >
      <p>{description}</p>
    </Modal>
  );
};

export default ConfirmModal;

const buttonStyle = css`
  flex: 1;
  min-width: 0;
  min-height: 44px;
  padding: 12px;
  border: 0;
  border-radius: 8px;
  font-size: 14px;
  font-weight: 500;
  line-height: 20px;
  cursor: pointer;
`;

const cancelButtonStyle = css`
  ${buttonStyle};
  background: ${theme.colors.background.neutralSubtle};
  color: ${theme.colors.foreground.neutral};
`;

const confirmButtonStyle = css`
  ${buttonStyle};
  background: ${theme.colors.background.criticalSolid};
  color: ${theme.colors.foreground.onBrand};
`;
