import { Navigate, Outlet, Route, Routes } from 'react-router-dom';
import AuthSplash from './auth/AuthSplash';
import RequireAuth from './auth/RequireAuth';
import AppShell from './layout/AppShell';
import RouteTransition, { RouteProgress, useRouteLag } from './layout/RouteTransition';
import { NAV_SECTIONS } from './layout/navigation';
import AssetDetailPage from './pages/catalog/AssetDetailPage';
import CatalogPage from './pages/catalog/CatalogPage';
import HomePage from './pages/HomePage';
import LoginPage from './pages/LoginPage';
import GovernancePage from './pages/governance/GovernancePage';
import NotBuiltYetPage from './pages/NotBuiltYetPage';
import ProfilePage from './pages/ProfilePage';
import PrincipalDetailPage from './pages/governance/PrincipalDetailPage';
import PrincipalsPage from './pages/governance/PrincipalsPage';
import PolicyBuilderPage from './pages/policies/PolicyBuilderPage';
import PolicyDetailPage from './pages/policies/PolicyDetailPage';
import PolicyListPage from './pages/policies/PolicyListPage';
import QueryPage from './pages/query/QueryPage';
import AccessRequestsPage from './pages/requests/AccessRequestsPage';
import SimulatorPage from './pages/simulator/SimulatorPage';
import AppRolesPage from './pages/settings/AppRolesPage';
import HomePersonasPage from './pages/settings/HomePersonasPage';
import LlmSettingsPage from './pages/settings/LlmSettingsPage';
import OpenMetadataSettingsPage from './pages/settings/OpenMetadataSettingsPage';
import SettingsPage from './pages/settings/SettingsPage';
import SourcesPage from './pages/SourcesPage';
import EnforcementPage from './pages/enforcement/EnforcementPage';
import ExpressionDocsPage from './pages/docs/ExpressionDocsPage';
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

  // The router renders a location that lags the address bar by the length of
  // the transition. Read here, above <Routes>, because overriding a location
  // installs a new context for everything below it.
  const { display, loading } = useRouteLag();

  return (
    <div className="tw:min-h-screen tw:bg-primary tw:text-primary tw:font-body">
      {/*
        Above the routes, so the curtain that covers signing in or out is not
        unmounted by the very navigation it is covering.
      */}
      <AuthSplash />

      {/* Driven by the real location, so the bar starts on the click. */}
      <RouteProgress active={loading} />

      <Routes location={display}>
        <Route element={<LoginPage />} path="/login" />

        <Route element={<GuardedLayout />}>
          <Route element={<HomePage />} path="/" />
          <Route element={<CatalogPage />} path="/catalog" />
          {/*
            Splat, not :fqn — an FQN is dotted and a service name may contain a
            slash, which a single dynamic segment would cut in half.
          */}
          <Route element={<AssetDetailPage />} path="/catalog/*" />
          <Route element={<GovernancePage />} path="/governance" />
          <Route element={<PrincipalsPage />} path="/principals" />
          <Route element={<PrincipalDetailPage />} path="/principals/:id" />
          <Route element={<PolicyListPage />} path="/policies" />
          <Route element={<PolicyBuilderPage />} path="/policies/new" />
          {/*
            Opening a policy shows it; /edit opens the form. Reading is by far
            the commoner errand, and the builder is not a safe place to land
            on the way to answering a question about somebody else's rule.
          */}
          <Route element={<PolicyDetailPage />} path="/policies/:id" />
          <Route element={<PolicyBuilderPage />} path="/policies/:id/edit" />
          <Route element={<QueryPage />} path="/query" />
          <Route element={<AccessRequestsPage />} path="/requests" />
          <Route element={<SimulatorPage />} path="/simulator" />
          <Route element={<SourcesPage />} path="/sources" />
          <Route element={<EnforcementPage />} path="/enforcement" />
          {/*
            Not under /principals/:id, though it renders much the same facts.
            That route is the directory -- somebody else's record, reached by
            searching for them. This one is reached from your own name in the
            corner and needs no id, so it keeps working when the directory is
            restricted to administrators.
          */}
          <Route element={<ProfilePage />} path="/profile" />
          <Route
            element={<OpenMetadataSettingsPage />}
            path="/settings/openmetadata"
          />
          <Route element={<AppRolesPage />} path="/settings/roles" />
          <Route element={<HomePersonasPage />} path="/settings/home" />
          <Route element={<LlmSettingsPage />} path="/settings/assistant" />
          <Route element={<SettingsPage />} path="/settings" />
          <Route element={<SystemStatusPage />} path="/system" />
          {/*
            Inside the guard, not outside it. The reference names the facets
            and attributes this deployment carries, which is a description of
            the organisation's data and not something to hand to a stranger.
          */}
          <Route element={<ExpressionDocsPage />} path="/docs/expressions" />
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
        <RouteTransition>
          <Outlet />
        </RouteTransition>
      </AppShell>
    </RequireAuth>
  );
}
