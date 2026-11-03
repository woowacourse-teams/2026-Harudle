import { Outlet } from 'react-router';
import LoadingSpinner from './shared/LoadingSpinner';
import useSessionRestore from './useSessionRestore';
import NativePushRegistration, {
  type NativePushControls,
} from './domain/push/NativePushRegistration';

const PublicFeedRoute = ({
  onPushChange,
}: {
  readonly onPushChange?: (controls: NativePushControls | null) => void;
}) => {
  const { isRestoring } = useSessionRestore();

  if (isRestoring) return <LoadingSpinner />;

  return (
    <>
      <NativePushRegistration onChange={onPushChange} />
      <Outlet />
    </>
  );
};

export default PublicFeedRoute;
