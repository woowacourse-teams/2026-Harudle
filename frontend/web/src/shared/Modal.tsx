import {
  useImperativeHandle,
  useRef,
  type MouseEvent,
  type ReactNode,
  type ReactPortal,
  type Ref,
} from 'react';
import { createPortal } from 'react-dom';
import { css } from '@emotion/react';
import { theme } from '../styles/theme';

export interface ModalHandle {
  open: () => void;
  close: () => void;
}

interface ModalProps {
  title: string;
  children: ReactNode;
  footer?: ReactNode;
  ref: Ref<ModalHandle>;
}

const Modal = ({ title, children, footer, ref }: ModalProps): ReactPortal => {
  const dialogRef = useRef<HTMLDialogElement>(null);

  useImperativeHandle(ref, (): ModalHandle => {
    return {
      open: (): void => {
        dialogRef.current?.showModal();
      },
      close: (): void => {
        dialogRef.current?.close();
      },
    };
  }, []);

  const handleBackdropClick = (e: MouseEvent<HTMLDialogElement>): void => {
    if (e.target === e.currentTarget) dialogRef.current?.close();
  };

  return createPortal(
    <dialog ref={dialogRef} css={dialogStyle} onClick={handleBackdropClick}>
      <div css={panelStyle}>
        <h2 css={titleStyle}>{title}</h2>
        <div css={contentStyle}>{children}</div>
        {footer && <footer css={footerStyle}>{footer}</footer>}
      </div>
    </dialog>,
    document.body,
  );
};

export default Modal;

const dialogStyle = css`
  position: fixed;
  inset: 0;
  width: min(382px, calc(100% - 32px));
  max-width: none;
  max-height: calc(100dvh - 32px);
  margin: auto;
  padding: 0;
  border: 0;
  border-radius: 12px;
  overflow: auto;
  background: ${theme.colors.background.surface};
  color: ${theme.colors.foreground.neutral};

  &::backdrop {
    background: ${theme.colors.background.backdrop};
  }
`;

const panelStyle = css`
  padding: 24px;
  letter-spacing: -0.35px;
`;

const titleStyle = css`
  margin: 0;
  padding-bottom: 8px;
  font-size: 18px;
  font-weight: 700;
  line-height: 26px;
`;

const contentStyle = css`
  color: ${theme.colors.foreground.neutralMuted};
  font-size: 14px;
  font-weight: 400;
  line-height: 22px;
`;

const footerStyle = css`
  display: flex;
  gap: 10px;
  padding-top: 24px;
`;
