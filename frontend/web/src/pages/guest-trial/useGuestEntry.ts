import { useEffect, useState } from 'react';
import { useNavigate } from 'react-router';
import type { ApiRequest } from '../../shared/api';
import { initializeGuestEntry, type GuestEntryResult } from './guestEntry';

type GuestEntryInitializer = () => Promise<GuestEntryResult>;

const useGuestEntry = (
  initialize: GuestEntryInitializer = initializeGuestEntry,
) => {
  const navigate = useNavigate();
  const [guestEntryRequest, setGuestEntryRequest] = useState<ApiRequest<void>>({
    status: 'loading',
  });

  useEffect(() => {
    let isActive = true;

    void initialize()
      .then((result) => {
        if (!isActive) {
          return;
        }

        if (result.status === 'authenticated') {
          navigate('/', { replace: true });
          return;
        }

        setGuestEntryRequest({ status: 'success', data: undefined });
      })
      .catch((error: unknown) => {
        if (isActive && error instanceof Error) {
          setGuestEntryRequest({ status: 'error', error });
        }
      });

    return () => {
      isActive = false;
    };
  }, [initialize, navigate]);

  return { guestEntryRequest };
};

export default useGuestEntry;
