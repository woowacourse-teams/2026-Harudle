import {
  useEffect,
  useImperativeHandle,
  useRef,
  useState,
  type Ref,
} from 'react';
import { createPortal } from 'react-dom';
import { css, Global } from '@emotion/react';
import Modal, { type ModalHandle } from '../../../shared/Modal';
import ActionButton from '../../../shared/ActionButton';
import { theme } from '../../../styles/theme';
import { getAdminPostcardImage } from '../adminApi';
import { ADMIN_POSTCARD_COPY as COPY } from '../copy';
import { PostcardCompositionError } from './composePostcard';
import { renderPostcard } from './renderPostcard';
import postcardFrame from './postcard-frame.webp';

export interface AdminPostcardModalHandle {
  readonly open: (generationId: string) => void;
}

interface Props {
  readonly ref: Ref<AdminPostcardModalHandle>;
}

type PostcardState =
  | { readonly status: 'idle' }
  | { readonly status: 'loading' }
  | {
      readonly status: 'ready';
      readonly url: string;
      readonly fileName: string;
    }
  | { readonly status: 'error'; readonly message: string };

// 4x6 포토프린터는 테두리 없음으로 뽑아도 가장자리가 조금 잘려 안쪽 여백을 둔다
const PRINT_SAFE_MARGIN_MM = 4;
const PRINT_SHEET_CLASS = 'admin-postcard-print-sheet';

/** 전체 생성 목록에서 고른 만화를 엽서 프레임에 넣어 미리 보고, 인쇄하거나 PNG로 저장한다. */
const AdminPostcardModal = ({ ref }: Props) => {
  const modalRef = useRef<ModalHandle>(null);
  const requestIdRef = useRef(0);
  const [state, setState] = useState<PostcardState>({ status: 'idle' });
  const previewUrl = state.status === 'ready' ? state.url : null;

  useEffect((): (() => void) | void => {
    if (!previewUrl) return;
    return (): void => URL.revokeObjectURL(previewUrl);
  }, [previewUrl]);

  useImperativeHandle(
    ref,
    (): AdminPostcardModalHandle => ({
      open: (generationId): void => {
        const requestId = requestIdRef.current + 1;
        requestIdRef.current = requestId;
        setState({ status: 'loading' });
        modalRef.current?.open();
        void loadPostcard(generationId).then((next): void => {
          if (requestIdRef.current === requestId) setState(next);
        });
      },
    }),
    [],
  );

  const close = (): void => {
    requestIdRef.current += 1;
    setState({ status: 'idle' });
    modalRef.current?.close();
  };

  return (
    <>
      <Modal
        ref={modalRef}
        title={COPY.title}
        dismissible={state.status !== 'loading'}
        footer={
          <>
            <ActionButton
              label={COPY.close}
              variant="secondary"
              onClick={close}
            />
            {state.status === 'ready' && (
              <>
                <a
                  css={downloadLinkStyle}
                  href={state.url}
                  download={state.fileName}
                >
                  {COPY.download}
                </a>
                <ActionButton
                  label={COPY.print}
                  onClick={(): void => window.print()}
                />
              </>
            )}
          </>
        }
      >
        <div css={bodyStyle}>
          {state.status === 'loading' && <p role="status">{COPY.loading}</p>}
          {state.status === 'error' && <p role="alert">{state.message}</p>}
          {state.status === 'ready' && (
            <>
              <img css={previewStyle} src={state.url} alt={COPY.previewAlt} />
              <p css={hintStyle}>{COPY.printHint}</p>
            </>
          )}
        </div>
      </Modal>
      {state.status === 'ready' &&
        createPortal(
          <div
            className={PRINT_SHEET_CLASS}
            css={printSheetStyle}
            aria-hidden="true"
          >
            <img src={state.url} alt="" />
          </div>,
          document.body,
        )}
      {state.status === 'ready' && <Global styles={printPageStyle} />}
    </>
  );
};

const loadPostcard = async (generationId: string): Promise<PostcardState> => {
  try {
    const { imageUrl, title } = await getAdminPostcardImage(generationId);
    const blob = await renderPostcard(postcardFrame, imageUrl);
    return {
      status: 'ready',
      url: URL.createObjectURL(blob),
      fileName: `harudle-postcard-${title ?? generationId}.png`,
    };
  } catch (caught: unknown) {
    return {
      status: 'error',
      message:
        caught instanceof PostcardCompositionError
          ? COPY.composeError
          : COPY.imageError,
    };
  }
};

export default AdminPostcardModal;

const bodyStyle = css`
  display: grid;
  justify-items: center;
  gap: 12px;
`;

const previewStyle = css`
  width: 100%;
  max-height: 60vh;
  object-fit: contain;
  border: 1px solid ${theme.colors.stroke.outline};
  border-radius: 8px;
`;

const hintStyle = css`
  margin: 0;
  font-size: 13px;
  color: ${theme.colors.foreground.neutralMuted};
`;

// ActionButton(secondary)과 같은 모양의 다운로드 링크
const downloadLinkStyle = css`
  display: flex;
  align-items: center;
  justify-content: center;
  width: 100%;
  height: 56px;
  padding: 16px 20px;
  border: 1px solid ${theme.colors.stroke.outline};
  border-radius: 24px;
  background-color: ${theme.colors.background.surface};
  color: ${theme.colors.foreground.neutral};
  font-size: 16px;
  font-weight: 500;
  line-height: 24px;
  text-decoration: none;
  white-space: nowrap;
`;

// 화면에서는 숨기고 인쇄할 때만 엽서 한 장만 보이게 한다
const printSheetStyle = css`
  display: none;
`;

const printPageStyle = css`
  @media print {
    @page {
      size: 100mm 148mm;
      margin: 0;
    }
    body > *:not(.${PRINT_SHEET_CLASS}) {
      display: none !important;
    }
    .${PRINT_SHEET_CLASS} {
      display: flex !important;
      align-items: center;
      justify-content: center;
      width: 100mm;
      height: 148mm;
      padding: ${PRINT_SAFE_MARGIN_MM}mm;
      box-sizing: border-box;
    }
    .${PRINT_SHEET_CLASS} img {
      width: 100%;
      height: 100%;
      object-fit: contain;
    }
  }
`;
