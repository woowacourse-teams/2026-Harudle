import { useEffect, useState } from 'react';
import { Navigate } from 'react-router';
import { API_BASE_URL } from '../../shared/api';
import { authFetch } from '../../shared/auth';
import AdminRoutes from './AdminRoutes';

type AdminAccess =
  'checking' | 'allowed' | 'unauthenticated' | 'forbidden' | 'error';

type CurrentUserAuthorization = {
  role: 'USER' | 'ADMIN';
};

const isCurrentUserAuthorization = (
  value: unknown,
): value is CurrentUserAuthorization => {
  return (
    typeof value === 'object' &&
    value !== null &&
    'role' in value &&
    (value.role === 'USER' || value.role === 'ADMIN')
  );
};

const AdminGuard = () => {
  const [access, setAccess] = useState<AdminAccess>('checking');

  useEffect(() => {
    let isActive = true;

    const checkAdminAccess = async (): Promise<void> => {
      if (localStorage.getItem('harudle.has-completed-oauth') === null) {
        if (isActive) setAccess('unauthenticated');
        return;
      }

      try {
        const response = await authFetch(`${API_BASE_URL}/me`);

        if (response.status === 401) {
          if (isActive) setAccess('unauthenticated');
          return;
        }

        if (!response.ok) {
          if (isActive) setAccess('error');
          return;
        }

        const data: unknown = await response.json();
        if (!isActive) return;

        if (!isCurrentUserAuthorization(data)) {
          setAccess('error');
          return;
        }

        setAccess(data.role === 'ADMIN' ? 'allowed' : 'forbidden');
      } catch {
        if (isActive) setAccess('error');
      }
    };

    void checkAdminAccess();

    return () => {
      isActive = false;
    };
  }, []);

  if (access === 'checking') {
    return <div role="status">관리자 권한을 확인하고 있어요.</div>;
  }

  if (access === 'unauthenticated') {
    return <Navigate to="/login" replace />;
  }

  if (access === 'forbidden') {
    return <Navigate to="/" replace />;
  }

  if (access === 'error') {
    return <div role="alert">관리자 권한을 확인하지 못했어요.</div>;
  }

  return <AdminRoutes />;
};

export default AdminGuard;
