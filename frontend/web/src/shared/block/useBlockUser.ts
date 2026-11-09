import { useCallback, useRef, useState } from 'react';
import { setUserBlocked } from '../../domain/blocks/blocks';
import type { ApiRequest } from '../api';
import { BLOCK_COPY } from './copy';

interface UseBlockUserProps {
  readonly userId: string | null;
}

interface BlockUserOptions {
  readonly onSuccess: () => void;
}

interface BlockUserState {
  readonly request: ApiRequest<void>;
  readonly reset: () => void;
  readonly execute: (options: BlockUserOptions) => Promise<void>;
}

const useBlockUser = ({ userId }: UseBlockUserProps): BlockUserState => {
  const pending = useRef(false);
  const [request, setRequest] = useState<ApiRequest<void>>({ status: 'idle' });

  const execute = useCallback(
    async ({ onSuccess }: BlockUserOptions): Promise<void> => {
      if (userId === null || pending.current) return;
      pending.current = true;
      setRequest({ status: 'loading' });
      try {
        await setUserBlocked(userId, true);
      } catch (caught: unknown) {
        setRequest({
          status: 'error',
          error:
            caught instanceof Error ? caught : new Error(BLOCK_COPY.failed),
        });
        return;
      } finally {
        pending.current = false;
      }
      setRequest({ status: 'success', data: undefined });
      onSuccess();
    },
    [userId],
  );

  const reset = (): void => {
    if (!pending.current) setRequest({ status: 'idle' });
  };

  return { request, reset, execute };
};

export default useBlockUser;
