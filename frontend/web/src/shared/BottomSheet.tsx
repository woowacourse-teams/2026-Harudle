import { css } from '@emotion/react';
import { useImperativeHandle, useRef, type ReactNode, type Ref } from 'react';
import { createPortal } from 'react-dom';
import { theme } from '../styles/theme';
import closeIcon from '../assets/icons/bottom-sheet-close.svg';

export interface BottomSheetHandle {
  open: () => void;
  close: () => void;
}

interface BottomSheetProps {
  title: string;
  children: ReactNode;
  ref: Ref<BottomSheetHandle>;
}

const BottomSheet = ({ title, children, ref }: BottomSheetProps) => {
  const dialogRef = useRef<HTMLDialogElement | null>(null);

  useImperativeHandle(ref, () => {
    return {
      open() {
        dialogRef.current?.showModal();
      },
      close() {
        dialogRef.current?.close();
      },
    };
  }, []);

  const handleBackdropClick = (e: React.MouseEvent<HTMLDialogElement>) => {
    if (e.target === e.currentTarget) {
      dialogRef.current?.close();
    }
  };

  return createPortal(
    <dialog ref={dialogRef} css={dialogStyle} onClick={handleBackdropClick}>
      <header css={headerStyle}>
        <h2 css={titleStyle}>{title}</h2>
        <button
          type="button"
          css={closeButtonStyle}
          onClick={() => {
            dialogRef.current?.close();
          }}
        >
          <img src={closeIcon} alt="닫기" width={24} height={24} />
        </button>
      </header>
      <div css={contentStyle}>{children}</div>
    </dialog>,
    document.body,
  );
};

export default BottomSheet;

const dialogStyle = css`
  position: fixed;
  inset: auto 0 0;
  margin: 0 auto;
  width: min(430px, 100%);
  max-width: none;
  max-height: 85dvh;
  overflow: auto;
  padding: 0;
  border: 0;
  border-radius: 16px 16px 0 0;
  background: ${theme.colors.background.surface};
  color: ${theme.colors.foreground.neutral};
  letter-spacing: -0.35px;
  transform: translateY(100%);
  opacity: 0;
  transition:
    transform 220ms cubic-bezier(0.4, 0, 1, 1),
    opacity 220ms ease,
    display 220ms allow-discrete,
    overlay 220ms allow-discrete;

  &[open] {
    transform: translateY(0);
    opacity: 1;
    transition-duration: 320ms;
    transition-timing-function: cubic-bezier(0.22, 1, 0.36, 1);
  }

  &::backdrop {
    background: ${theme.colors.background.backdrop};
    opacity: 0;
    transition:
      opacity 220ms ease,
      display 220ms allow-discrete,
      overlay 220ms allow-discrete;
  }

  &[open]::backdrop {
    opacity: 1;
    transition-duration: 320ms;
  }

  @starting-style {
    &[open] {
      transform: translateY(100%);
      opacity: 0;
    }

    &[open]::backdrop {
      opacity: 0;
    }
  }
`;

const headerStyle = css`
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  min-height: 60px;
  padding: 16px 20px 12px;
  border-bottom: 1px solid ${theme.colors.stroke.divider};
`;

const titleStyle = css`
  margin: 0;
  min-width: 0;
  font-size: 17px;
  font-weight: 700;
  line-height: 24px;
`;

const closeButtonStyle = css`
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
  width: 40px;
  height: 40px;
  padding: 0;
  border: 0;
  background: transparent;
  cursor: pointer;
`;

const contentStyle = css`
  padding: 8px 20px;
`;
