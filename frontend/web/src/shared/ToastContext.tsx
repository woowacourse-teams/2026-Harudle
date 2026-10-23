import { css, keyframes, type SerializedStyles } from '@emotion/react';
import {
  createContext,
  useCallback,
  useContext,
  useReducer,
  type ReactNode,
} from 'react';
import { createPortal } from 'react-dom';
import Toast, { type ToastProps } from './Toast';
import { ERROR_MESSAGES } from './errorMessage';

interface ToastItem extends ToastProps {
  readonly id: string;
  readonly durationMs: number | 'infinite';
}

interface AddToastAction {
  readonly type: 'add';
  readonly toast: ToastItem;
}

interface RemoveToastAction {
  readonly type: 'remove';
  readonly id: ToastItem['id'];
}

interface UpdateToastAction {
  readonly type: 'update';
  readonly id: ToastItem['id'];
  readonly toast: Partial<ToastProps>;
}

type ToastAction = AddToastAction | RemoveToastAction | UpdateToastAction;

const TOAST_MAX_LENGTH = 2;

interface ToastContextValue {
  readonly addToast: (
    toast: ToastProps,
    durationMs: number | 'infinite',
    id?: ToastItem['id'],
  ) => ToastItem['id'];
  readonly deleteToast: (id: ToastItem['id']) => void;
  readonly updateToast: (
    id: ToastItem['id'],
    toast: Partial<ToastProps>,
  ) => void;
}

const ToastContext = createContext<ToastContextValue | null>(null);

const toastReducer = (
  toasts: readonly ToastItem[],
  action: ToastAction,
): readonly ToastItem[] => {
  switch (action.type) {
    case 'add': {
      if (toasts.some((toast): boolean => toast.id === action.toast.id)) {
        return toasts;
      }

      const nextToasts = [...toasts, action.toast];
      if (nextToasts.length <= TOAST_MAX_LENGTH) {
        return nextToasts;
      }

      const oldestFiniteToastIndex = nextToasts.findIndex(
        (toast): boolean => toast.durationMs !== 'infinite',
      );
      return nextToasts.filter(
        (_, index): boolean => index !== oldestFiniteToastIndex,
      );
    }
    case 'remove':
      return toasts.filter((toast): boolean => toast.id !== action.id);
    case 'update':
      return toasts.map((toast): ToastItem => {
        return toast.id === action.id ? { ...toast, ...action.toast } : toast;
      });
  }
};

export const ToastProvider = ({ children }: { children: ReactNode }) => {
  const [toasts, dispatch] = useReducer(toastReducer, []);

const addToast = useCallback(
  (
    toast: ToastProps,
    durationMs: number | 'infinite',
    id: ToastItem['id'] = crypto.randomUUID(),
  ): ToastItem['id'] => {
    dispatch({ type: 'add', toast: { ...toast, id, durationMs } });

    if (durationMs !== 'infinite') {
      setTimeout((): void => {
        dispatch({ type: 'remove', id });
      }, durationMs);
    }

    return id;
  },
  [],
);

  const deleteToast = (id: ToastItem['id']): void => {
    dispatch({ type: 'remove', id });
  };

  const updateToast = (
    id: ToastItem['id'],
    toast: Partial<ToastProps>,
  ): void => {
    dispatch({ type: 'update', id, toast });
  };

  return (
    <ToastContext.Provider
      value={{ addToast, deleteToast, updateToast }}
    >

      {children}
      {createPortal(
        <div css={toastListStyle}>
          {toasts.map(({ id, durationMs, ...toast }) => (
            <div key={id} css={toastAnimationStyle(durationMs)}>
              <Toast {...toast} />
            </div>
          ))}
        </div>,
        document.body,
      )}
    </ToastContext.Provider>
  );
};

export const useToastContext = (): ToastContextValue => {
  const context = useContext(ToastContext);

  if (!context) {
    throw new Error(ERROR_MESSAGES.TOAST_PROVIDER_REQUIRED);
  }

  return context;
};

const toastListStyle = css`
  position: fixed;
  top: 68px;
  left: 0;
  right: 0;
  z-index: 20;
  display: flex;
  flex-direction: column;
  gap: 8px;
  width: calc(100% - 40px);
  max-width: 390px;
  margin: 0 auto;

  @media (prefers-reduced-motion: reduce) {
    & > div {
      animation: none;
    }
  }
`;

const toastEnter = keyframes`
  from {
    opacity: 0;
    transform: translateY(4px) scale(0.985);
  }
  70% {
    opacity: 1;
    transform: translateY(-1px) scale(1);
  }
  to {
    opacity: 1;
    transform: translateY(0) scale(1);
  }
`;

const toastExit = keyframes`
  from {
    opacity: 1;
    transform: translateY(0) scale(1);
  }
  to {
    opacity: 0;
    transform: translateY(-2px) scale(0.99);
  }
`;

const toastAnimationStyle = (
  durationMs: ToastItem['durationMs'],
): SerializedStyles => {
  if (durationMs === 'infinite') {
    return css`
      animation: ${toastEnter} 280ms cubic-bezier(0.22, 1, 0.36, 1) both;
    `;
  }

  return css`
    animation:
      ${toastEnter} 280ms cubic-bezier(0.22, 1, 0.36, 1) both,
      ${toastExit} 160ms ease-out ${Math.max(durationMs - 160, 0)}ms forwards;
  `;
};
