import { Navigate, Outlet, Route, Routes } from 'react-router-dom';
import RequireAuth from './auth/RequireAuth';
import AppShell from './layout/AppShell';
import { NAV_SECTIONS } from './layout/navigation';
import HomePage from './pages/HomePage';
import LoginPage from './pages/LoginPage';
import NotBuiltYetPage from './pages/NotBuiltYetPage';
import SystemStatusPage from './pages/SystemStatusPage';

/**
 * Routes.
 *
 * /login is the only public one. Everything else is a child of the guarded
 * layout route, so a screen added later sits behind the guard by belonging to
 * the tree rather than by somebody remembering to wrap it.
 */
export default function App() {
  const placeholders = NAV_SECTIONS.filter((section) => section.milestone);

  return (
    <div className="tw:min-h-screen tw:bg-primary tw:text-primary tw:font-body">
      <Routes>
        <Route element={<LoginPage />} path="/login" />

        <Route element={<GuardedLayout />}>
          <Route element={<HomePage />} path="/" />
          <Route element={<SystemStatusPage />} path="/system" />
          {placeholders.map((section) => (
            <Route
              element={<NotBuiltYetPage />}
              key={section.href}
              path={section.href}
            />
          ))}
          <Route element={<Navigate replace to="/" />} path="*" />
        </Route>
      </Routes>
    </div>
  );
}

function GuardedLayout() {
  return (
    <RequireAuth>
      <AppShell>
        <Outlet />
      </AppShell>
    </RequireAuth>
  );
}
