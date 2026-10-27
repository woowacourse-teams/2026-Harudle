import { useEffect, useRef, useState } from 'react';
import { setUserBlocked, type BlockedUser } from '../../domain/blocks/blocks';

interface UnblockUserOptions {
  readonly onSuccess: () => void | Promise<void>;
  readonly onError: () => void;
}

interface UnblockUserState {
  readonly pendingUserId: BlockedUser['userId'] | null;
  readonly execute: (
    userId: BlockedUser['userId'],
    options: UnblockUserOptions,
  ) => Promise<void>;
}

const useUnblockUser = (): UnblockUserState => {
  const [pendingUserId, setPendingUserId] = useState<
    BlockedUser['userId'] | null
  >(null);
  const pendingRef = useRef(false);
  const mountedRef = useRef(false);

  useEffect((): (() => void) => {
    mountedRef.current = true;
    return (): void => {
      mountedRef.current = false;
    };
  }, []);

  const execute = async (
    userId: BlockedUser['userId'],
    { onSuccess, onError }: UnblockUserOptions,
  ): Promise<void> => {
    if (pendingRef.current || !mountedRef.current) return;
    pendingRef.current = true;
    setPendingUserId(userId);
    try {
      try {
        await setUserBlocked(userId, false);
      } catch {
        if (mountedRef.current) onError();
        return;
      }
      await onSuccess();
    } finally {
      pendingRef.current = false;
      if (mountedRef.current) setPendingUserId(null);
    }
  };

  return { pendingUserId, execute };
};

export default useUnblockUser;
