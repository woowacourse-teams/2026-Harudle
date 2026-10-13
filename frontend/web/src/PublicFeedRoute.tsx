import { Outlet } from 'react-router';
import LoadingSpinner from './shared/LoadingSpinner';
import useSessionRestore from './useSessionRestore';

const PublicFeedRoute = () => {
  const { isRestoring } = useSessionRestore();

  if (isRestoring) return <LoadingSpinner />;

  return <Outlet />;
};

export default PublicFeedRoute;
