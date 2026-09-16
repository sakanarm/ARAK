import { Navigate, Outlet, Route, Routes } from 'react-router-dom';
import RequireAuth from './auth/RequireAuth';
import AppShell from './layout/AppShell';
import { NAV_SECTIONS } from './layout/navigation';
import AssetDetailPage from './pages/catalog/AssetDetailPage';
import CatalogPage from './pages/catalog/CatalogPage';
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
          <Route element={<CatalogPage />} path="/catalog" />
          {/*
            Splat, not :fqn — an FQN is dotted and a service name may contain a
            slash, which a single dynamic segment would cut in half.
          */}
          <Route element={<AssetDetailPage />} path="/catalog/*" />
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
