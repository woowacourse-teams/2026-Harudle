import { Navigate, Outlet } from 'react-router';
import { HAS_SEEN_LANDING_KEY } from './shared/constants';

const EntryRoute = () => {
  let hasSeenLanding = true;
  try {
    hasSeenLanding = localStorage.getItem(HAS_SEEN_LANDING_KEY) === 'true';
  } catch {
    // 저장소를 사용할 수 없으면 반복 이동 없이 공개 피드를 표시한다.
  }

  if (!hasSeenLanding) return <Navigate to="/landing" replace />;

  return <Outlet />;
};

export default EntryRoute;
