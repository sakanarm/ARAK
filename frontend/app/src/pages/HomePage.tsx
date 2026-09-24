import { useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useNavigate } from 'react-router-dom';
import { Edit03, Plus, SearchLg } from '@untitledui/icons';
import { Button } from '@openmetadata/ui-core-components/components/base/buttons/button';
import { apiErrorMessage } from '../api/client';
import {
  fetchHomeLayout,
  resetHomeLayout,
  saveHomeLayout,
  type HomeLayout,
} from '../api/home';
import { useAuthStore } from '../auth/authStore';
import { humaniseRole } from '../layout/AppShell';
import { HomeEditor } from './home/HomeEditor';
import { columnsOf, gridStyle } from './home/presets';
import { HomeWidgetView, Loading } from './home/widgets';
import mark from '../assets/arak-mark.png';

/**
 * Landing view — the page each account arranges for itself (M12).
 *
 * It used to be a fixed grid of governance panels, which was the right page for
 * exactly the people who built it. Somebody who signs in to find a table and
 * query it was being handed a stream of recent policies, a coverage meter and a
 * list of registered sources: nothing they can act on, above the one thing they
 * came for. So the arrangement is stored per account, the default depends on
 * what the account does, and the governance panels are not offered to accounts
 * that do not govern.
 *
 * That last part is a choice about what is worth showing, not a security
 * boundary, and it is worth being plain about why: every endpoint behind those
 * panels answers any signed-in account on purpose, because a person who is
 * refused data has to be able to see which policy refused them. Narrowing this
 * page narrows nobody's rights. The server makes the same point in
 * `HomeLayout.WidgetType#governance()`, and both are the menu rather than the
 * boundary — the same phrase the navigation rail uses for the same reason.
 */

/** Who is offered the governance widgets. Mirrors `HomeResource`. */
const GOVERNANCE_ROLES = [
  'PLATFORM_ADMIN',
  'POLICY_AUTHOR',
  'DATA_OWNER',
  'AUDITOR',
];

export default function HomePage() {
  const user = useAuthStore((state) => state.user);
  const roles = user?.roles ?? [];
  const governs = roles.some((role) => GOVERNANCE_ROLES.includes(role));

  const client = useQueryClient();
  const { data: view, isLoading } = useQuery({
    queryKey: ['home-layout'],
    queryFn: fetchHomeLayout,
    retry: false,
  });

  // Null while not editing. Holding the draft here rather than inside the
  // editor is what lets the page below re-render as things move: you arrange
  // the page by looking at the page.
  const [draft, setDraft] = useState<HomeLayout | null>(null);
  const [error, setError] = useState<string | null>(null);

  const accept = (next: { layout: HomeLayout }) => {
    // What comes back is what was stored, after the server cleaned it — so the
    // page shows the saved truth rather than the draft that was posted.
    client.setQueryData(['home-layout'], next);
    setDraft(null);
    setError(null);
  };

  const save = useMutation({
    mutationFn: saveHomeLayout,
    onSuccess: accept,
    onError: (cause) =>
      setError(apiErrorMessage(cause, 'That arrangement could not be saved.')),
  });

  const reset = useMutation({
    mutationFn: resetHomeLayout,
    onSuccess: accept,
    onError: (cause) =>
      setError(apiErrorMessage(cause, 'The page could not be reset.')),
  });

  const layout = draft ?? view?.layout ?? null;
  const editing = draft !== null;

  return (
    <div className="tw:flex tw:flex-col tw:gap-5">
      <Greeting
        editing={editing}
        governs={governs}
        name={user?.displayName || user?.username || ''}
        onEdit={() => {
          setError(null);
          setDraft(view?.layout ?? null);
        }}
        roles={roles}
      />

      {editing && layout && (
        <HomeEditor
          draft={layout}
          error={error}
          governanceReader={governs}
          onCancel={() => {
            setDraft(null);
            setError(null);
          }}
          onChange={setDraft}
          onReset={() => reset.mutate()}
          onSave={() => save.mutate(layout)}
          saving={save.isPending || reset.isPending}
        />
      )}

      {!editing && error && (
        <p className="tw:rounded-lg tw:bg-utility-error-50 tw:px-3 tw:py-2 tw:text-sm tw:text-utility-error-700">
          {error}
        </p>
      )}

      {isLoading || !layout ? (
        <div className="tw:rounded-xl tw:border tw:border-secondary tw:bg-primary tw:px-5 tw:py-4 tw:shadow-xs">
          <Loading />
        </div>
      ) : layout.widgets.length === 0 ? (
        <div className="tw:rounded-xl tw:border tw:border-dashed tw:border-secondary tw:px-5 tw:py-10 tw:text-center">
          <p className="tw:text-sm tw:text-tertiary">
            This page is empty. Add a panel, or reset it to the default.
          </p>
        </div>
      ) : (
        <div className="arak-home-grid" style={gridStyle(layout.preset)}>
          {Array.from({ length: columnsOf(layout.preset) }, (_, column) => (
            <div className="tw:flex tw:min-w-0 tw:flex-col tw:gap-5" key={column}>
              {layout.widgets
                .filter((widget) => widget.column === column)
                .map((widget) => (
                  <HomeWidgetView key={widget.id} widget={widget} />
                ))}
            </div>
          ))}
        </div>
      )}
    </div>
  );
}

/**
 * The banner OpenMetadata opens with, in our colours.
 *
 * It carries the two actions somebody signing in actually wants — which is not
 * the same pair for everybody. An account that cannot write a policy was being
 * offered "New policy" as its primary action, which is a button that exists to
 * refuse them.
 */
function Greeting({
  name,
  roles,
  governs,
  editing,
  onEdit,
}: {
  name: string;
  roles: string[];
  governs: boolean;
  editing: boolean;
  onEdit: () => void;
}) {
  const firstName = name.split(' ')[0];
  // `onPress` + navigate, never `href`: the library's Button renders a real
  // anchor when given one, and a real anchor reloads the whole app — which on
  // this page means throwing away every query the dashboard just ran.
  const navigate = useNavigate();

  return (
    <section
      className="tw:relative tw:overflow-hidden tw:rounded-2xl tw:px-6 tw:py-7 tw:sm:px-8"
      style={{
        backgroundImage:
          'linear-gradient(120deg, var(--color-brand-700, #175cd3) 0%,' +
          ' var(--color-brand-600, #1570ef) 45%,' +
          ' var(--color-brand-500, #2e90fa) 100%)',
      }}>
      <img
        aria-hidden
        alt=""
        className="tw:pointer-events-none tw:absolute tw:-right-6 tw:-bottom-10 tw:hidden tw:size-56 tw:opacity-15 tw:sm:block"
        src={mark}
      />

      <div className="tw:relative tw:max-w-2xl">
        <p className="tw:text-sm tw:font-medium tw:text-white/80">
          Data access control
        </p>
        <h1 className="tw:mt-1 tw:text-display-sm tw:font-semibold tw:text-white">
          {firstName ? `Welcome back, ${firstName}` : 'Welcome'}
        </h1>
        <p className="tw:mt-2 tw:text-md tw:text-white/85">
          {governs
            ? 'Subscription and data policies over your OpenMetadata governance — composed from organisation down to a single column, and enforced in the database itself.'
            : 'Search the catalog for the data you need. What you can see in it, and what comes back masked, is decided by the policies written over it.'}
        </p>

        {roles.length > 0 && (
          <div className="tw:mt-4 tw:flex tw:flex-wrap tw:gap-1.5">
            {roles.map((role) => (
              <span
                className="tw:rounded-full tw:bg-white/15 tw:px-2.5 tw:py-1 tw:text-xs tw:font-medium tw:text-white"
                key={role}>
                {humaniseRole(role)}
              </span>
            ))}
          </div>
        )}

        {/*
          items-center, or the link button stretches to the filled button's
          height and its label rides the top of that box instead of sitting on
          the same line as the first action.
        */}
        <div className="tw:mt-6 tw:flex tw:flex-wrap tw:items-center tw:gap-3">
          {governs ? (
            <Button
              color="secondary"
              iconLeading={Plus}
              onPress={() => navigate('/policies/new')}
              size="md">
              New policy
            </Button>
          ) : (
            <Button
              color="secondary"
              iconLeading={SearchLg}
              onPress={() => navigate('/catalog')}
              size="md">
              Explore the catalog
            </Button>
          )}
          <Button
            className="tw:self-center"
            color="link-gray"
            onPress={() => navigate(governs ? '/catalog' : '/query')}
            size="md">
            <span className="tw:text-white">
              {governs ? 'Explore the catalog' : 'Run a query'}
            </span>
          </Button>
          {!editing && (
            <Button
              className="tw:self-center"
              color="link-gray"
              iconLeading={Edit03}
              onPress={onEdit}
              size="md">
              <span className="tw:text-white">Edit this page</span>
            </Button>
          )}
        </div>
      </div>
    </section>
  );
}
