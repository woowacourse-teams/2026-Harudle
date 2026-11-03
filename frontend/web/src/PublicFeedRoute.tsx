import { Outlet } from 'react-router';
import LoadingSpinner from './shared/LoadingSpinner';
import useSessionRestore from './useSessionRestore';
import NativePushRegistration from './domain/push/NativePushRegistration';

const PublicFeedRoute = () => {
  const { isRestoring } = useSessionRestore();

  if (isRestoring) return <LoadingSpinner />;

  return (
    <>
      <NativePushRegistration />
      <Outlet />
    </>
  );
};

export default PublicFeedRoute;
