import { Navigate, Outlet } from 'react-router';
import { HAS_SEEN_LANDING_KEY } from './shared/constants';

const EntryRoute = () => {
  let hasSeenLanding = true;
  let hasCompletedOAuth = false;
  try {
    hasSeenLanding = localStorage.getItem(HAS_SEEN_LANDING_KEY) === 'true';
    hasCompletedOAuth =
      localStorage.getItem('harudle.has-completed-oauth') !== null;
  } catch {
    // 저장소를 사용할 수 없으면 반복 이동 없이 공개 피드를 표시한다.
    hasSeenLanding = true;
  }

  // App의 세션 복원·약관 검사 뒤에 실행된다. 이 표시는 첫 방문 안내만 건너뛰는 UX 힌트다.
  if (!hasSeenLanding && !hasCompletedOAuth)
    return <Navigate to="/landing" replace />;

  return <Outlet />;
};

export default EntryRoute;
