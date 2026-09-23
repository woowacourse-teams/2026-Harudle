import { Route, Routes } from 'react-router';
import AdminPage from './AdminPage';

const AdminRoutes = () => (
  <Routes>
    <Route path="*" element={<AdminPage />} />
  </Routes>
);

export default AdminRoutes;
