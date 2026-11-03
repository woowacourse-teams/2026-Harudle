import { useEffect, useRef } from 'react';
import { useNavigate, useSearchParams } from 'react-router';
import { clearWithdrawalSession } from '../../domain/user/withdrawal';
import { useAnalytics } from '../../posthog/useAnalytics';

const WithdrawalCallbackPage = () => {
  const navigate = useNavigate();
  const [searchParams] = useSearchParams();
  const { resetUser } = useAnalytics();
  const hasHandledWithdrawal = useRef(false);
  const withdrawal = searchParams.get('withdrawal');

  useEffect((): void => {
    if (hasHandledWithdrawal.current) return;
    hasHandledWithdrawal.current = true;

    if (withdrawal === 'success') {
      clearWithdrawalSession();
      resetUser();
      navigate('/', { replace: true });
      return;
    }

    alert('회원 탈퇴 요청을 처리하지 못했어요. 다시 시도해 주세요.');
    navigate('/setting', { replace: true });
  }, [withdrawal, resetUser, navigate]);

  return null;
};

export default WithdrawalCallbackPage;
