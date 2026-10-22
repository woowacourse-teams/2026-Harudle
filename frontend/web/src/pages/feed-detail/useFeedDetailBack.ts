import { useLocation, useNavigate } from 'react-router';

const useFeedDetailBack = (): (() => void) => {
  const navigate = useNavigate();
  const location = useLocation();
  const state: unknown = location.state;

  return (): void => {
    if (
      typeof state === 'object' &&
      state !== null &&
      'fromFeed' in state &&
      state.fromFeed === true
    ) {
      void navigate(-1);
      return;
    }

    void navigate('/', { replace: true });
  };
};

export default useFeedDetailBack;
