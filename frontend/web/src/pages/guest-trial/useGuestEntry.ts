import { useCallback, useEffect, useRef, useState } from 'react';
import { useNavigate } from 'react-router';
import type { ApiRequest } from '../../shared/api';
import { initializeGuestEntry, type GuestEntryResult } from './guestEntry';

type GuestEntryInitializer = () => Promise<GuestEntryResult>;

const useGuestEntry = (
  initialize: GuestEntryInitializer = initializeGuestEntry,
) => {
  const navigate = useNavigate();
  const navigateRef = useRef(navigate);
  const [guestEntryRequest, setGuestEntryRequest] = useState<ApiRequest<void>>({
    status: 'loading',
  });
  const [requestAttempt, setRequestAttempt] = useState(0);

  useEffect(() => {
    navigateRef.current = navigate;
  }, [navigate]);

  useEffect(() => {
    let isActive = true;

    void initialize()
      .then((result) => {
        if (!isActive) {
          return;
        }

        if (result.status === 'authenticated') {
          navigateRef.current('/', { replace: true });
          return;
        }

        setGuestEntryRequest({ status: 'success', data: undefined });
      })
      .catch((error: unknown) => {
        if (isActive) {
          setGuestEntryRequest({
            status: 'error',
            error:
              error instanceof Error
                ? error
                : new Error('게스트 체험을 준비하지 못했습니다'),
          });
        }
      });

    return () => {
      isActive = false;
    };
  }, [initialize, requestAttempt]);

  const retryGuestEntry = useCallback(() => {
    setGuestEntryRequest({ status: 'loading' });
    setRequestAttempt((attempt) => attempt + 1);
  }, []);

  return { guestEntryRequest, retryGuestEntry };
};

export default useGuestEntry;
