import { css } from '@emotion/react';
import { useEffect, useRef, useState } from 'react';
import {
  clearWithdrawalSession,
  getWithdrawalErrorMessage,
  requestWithdrawal,
} from '../../domain/user/withdrawal';
import { useAnalytics } from '../../posthog/useAnalytics';
import Modal, { type ModalHandle } from '../../shared/Modal';
import { theme } from '../../styles/theme';

interface WithdrawalModalProps {
  readonly onClose: () => void;
}

const WithdrawalModal = ({ onClose }: WithdrawalModalProps) => {
  const modalRef = useRef<ModalHandle>(null);
  const submitting = useRef(false);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [manualUnlink, setManualUnlink] = useState(false);
  const { resetUser } = useAnalytics();

  useEffect((): void => {
    modalRef.current?.open();
  }, []);

  const withdraw = async (): Promise<void> => {
    if (submitting.current) return;
    submitting.current = true;
    setLoading(true);
    setError(null);

    try {
      const result = await requestWithdrawal();
      if (result.status === 'AUTHENTICATION_REQUIRED') {
        window.location.assign(result.authorizationUrl);
        return;
      }
      clearWithdrawalSession();
      resetUser();
      if (result.appleManualUnlinkRequired) {
        setManualUnlink(true);
        return;
      }
      window.location.replace('/');
    } catch (cause: unknown) {
      setError(getWithdrawalErrorMessage(cause));
    } finally {
      submitting.current = false;
      setLoading(false);
    }
  };

  if (manualUnlink) {
    return (
      <Modal
        ref={modalRef}
        css={centeredStyle}
        title="회원 탈퇴가 완료됐어요"
        dismissible={false}
        footer={
          <button
            type="button"
            css={primaryStyle}
            onClick={(): void => window.location.replace('/')}
          >
            확인
          </button>
        }
      >
        <p>애플 계정 설정에서 하루들 연결을 직접 해제해 주세요.</p>
        <a
          href="https://support.apple.com/ko-kr/guide/iphone/iph238921d37/ios"
          target="_blank"
          rel="noreferrer"
        >
          애플 연결 해제 방법 보기
        </a>
      </Modal>
    );
  }

  return (
    <Modal
      ref={modalRef}
      css={centeredStyle}
      title="정말 탈퇴하시겠어요?"
      dismissible={false}
      footer={
        <>
          <button
            type="button"
            css={secondaryStyle}
            disabled={loading}
            onClick={onClose}
          >
            더 둘러보기
          </button>
          <button
            type="button"
            css={primaryStyle}
            disabled={loading}
            onClick={(): void => {
              void withdraw();
            }}
          >
            {loading ? '처리 중...' : '탈퇴하기'}
          </button>
        </>
      }
    >
      <p>
        탈퇴하면 계정, 일기와 작성한 피드가
        <br /> 삭제되며 복구할 수 없어요.
      </p>
      {error && (
        <p css={errorStyle} role="alert">
          {error}
        </p>
      )}
    </Modal>
  );
};

export default WithdrawalModal;

const centeredStyle = css`
  text-align: center;
`;

const buttonStyle = css`
  flex: 1;
  min-height: 48px;
  border: 0;
  border-radius: 10px;
  font-size: 15px;
  font-weight: 600;
  cursor: pointer;
  &:disabled {
    opacity: 0.5;
    cursor: default;
  }
`;
const secondaryStyle = css`
  ${buttonStyle};
  background: ${theme.colors.background.surface};
  border: 1px solid ${theme.colors.stroke.outline};
  color: ${theme.colors.foreground.neutral};
`;
const primaryStyle = css`
  ${buttonStyle};
  background: ${theme.colors.foreground.critical};
  color: white;
`;
const errorStyle = css`
  color: ${theme.colors.foreground.critical};
`;
