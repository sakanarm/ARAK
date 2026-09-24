import { useMemo, useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Edit03, RefreshCcw01 } from '@untitledui/icons';
import { Button } from '@openmetadata/ui-core-components/components/base/buttons/button';
import { apiErrorMessage } from '../../api/client';
import {
  fetchHomePersonas,
  resetHomePersona,
  saveHomePersona,
  HOME_PERSONA_LABELS,
  HOME_PERSONA_ROLES,
  type HomeLayout,
  type HomePersonaLayout,
  type HomePersonaRole,
} from '../../api/home';
import { useAuthStore } from '../../auth/authStore';
import { HomeEditor } from '../home/HomeEditor';
import { columnsOf, gridStyle } from '../home/presets';
import { HomeWidgetView, Loading } from '../home/widgets';

/**
 * The home page each role starts on (M12b).
 *
 * Until now the first page somebody saw was decided in code, which meant the
 * only way to give auditors a different landing page from requesters was a
 * release. This is that decision moved onto the screen: five pages, one per
 * platform role, each of them what somebody with that role is handed until
 * they arrange their own.
 *
 * Two properties of this screen are worth stating plainly, because they are
 * what make it safe to hand to an administrator:
 *
 * 1. **It is a starting point, not a mandate.** The moment somebody arranges
 *    their own page, nothing saved here reaches them again. So this cannot be
 *    used to put a panel in front of somebody who removed it — which is the
 *    thing an administrator would otherwise eventually try, and be right to
 *    expect to work, and be wrong.
 * 2. **It is the one editor whose output lands in other people's sessions.**
 *    The server cleans every layout on the way in and again on the way out, and
 *    that is not decoration here: a note widget saved onto the policy-author
 *    page is rendered for every policy author who has not customised theirs.
 */

/** Which roles are offered the governance widgets. Mirrors `HomeResource`. */
const GOVERNANCE_ROLES: HomePersonaRole[] = [
  'PLATFORM_ADMIN',
  'POLICY_AUTHOR',
  'DATA_OWNER',
  'AUDITOR',
];

/** What each role's page is for, in the words somebody choosing would use. */
const PERSONA_BLURBS: Record<HomePersonaRole, string> = {
  PLATFORM_ADMIN:
    'Runs this platform. Sources, connections and what is enforced where.',
  POLICY_AUTHOR: 'Writes policy. Drafts, recent changes and what they cover.',
  DATA_OWNER: 'Answers for particular data. Their sources and who reads them.',
  AUDITOR: 'Reads the record. Coverage, decisions and evidence.',
  REQUESTER:
    'Came to find a table and query it. Everybody holds this one, so it is the page most people see.',
};

export default function HomePersonasPage() {
  const isAdmin = useAuthStore((state) =>
    (state.user?.roles ?? []).includes('PLATFORM_ADMIN')
  );

  const client = useQueryClient();
  const { data: personas, isLoading } = useQuery({
    queryKey: ['home-personas'],
    queryFn: fetchHomePersonas,
    retry: false,
    enabled: isAdmin,
  });

  const [editing, setEditing] = useState<HomePersonaRole | null>(null);
  const [draft, setDraft] = useState<HomeLayout | null>(null);
  const [error, setError] = useState<string | null>(null);

  const byRole = useMemo(() => {
    const map = new Map<HomePersonaRole, HomePersonaLayout>();
    for (const row of personas ?? []) map.set(row.role, row);
    return map;
  }, [personas]);

  const accept = (next: HomePersonaLayout) => {
    client.setQueryData<HomePersonaLayout[]>(['home-personas'], (current) =>
      (current ?? []).map((row) => (row.role === next.role ? next : row))
    );
    setEditing(null);
    setDraft(null);
    setError(null);
  };

  const save = useMutation({
    mutationFn: ({ role, layout }: { role: HomePersonaRole; layout: HomeLayout }) =>
      saveHomePersona(role, layout),
    onSuccess: accept,
    onError: (cause) =>
      setError(apiErrorMessage(cause, 'That arrangement could not be saved.')),
  });

  const reset = useMutation({
    mutationFn: (role: HomePersonaRole) => resetHomePersona(role),
    onSuccess: accept,
    onError: (cause) =>
      setError(apiErrorMessage(cause, 'That page could not be reset.')),
  });

  if (!isAdmin) {
    return (
      <div className="tw:rounded-xl tw:border tw:border-secondary tw:bg-primary tw:px-5 tw:py-10 tw:text-center tw:shadow-xs">
        <p className="tw:text-sm tw:text-tertiary">
          Arranging the page a role starts on is a platform administrator's job.
          Your own page is yours to arrange, from Home.
        </p>
      </div>
    );
  }

  return (
    <div className="tw:flex tw:flex-col tw:gap-5">
      <header>
        <h1 className="tw:text-xl tw:font-semibold tw:text-primary">
          Home page per role
        </h1>
        <p className="tw:mt-1 tw:max-w-3xl tw:text-sm tw:text-tertiary">
          What somebody sees on their first morning, chosen by the role they
          hold. A page saved here is a starting point: once a person arranges
          their own, nothing set here moves it again. Somebody holding several
          roles is served the first one below that has been arranged.
        </p>
      </header>

      {error && !editing && (
        <p className="tw:rounded-lg tw:bg-utility-error-50 tw:px-3 tw:py-2 tw:text-sm tw:text-utility-error-700">
          {error}
        </p>
      )}

      {isLoading ? (
        <div className="tw:rounded-xl tw:border tw:border-secondary tw:bg-primary tw:px-5 tw:py-4 tw:shadow-xs">
          <Loading />
        </div>
      ) : (
        HOME_PERSONA_ROLES.map((role) => {
          const persona = byRole.get(role);
          if (!persona) return null;
          const open = editing === role;
          const shown = open && draft ? draft : persona.layout;
          const governs = GOVERNANCE_ROLES.includes(role);

          return (
            <section
              className="tw:rounded-xl tw:border tw:border-secondary tw:bg-primary tw:shadow-xs"
              key={role}>
              <header className="tw:flex tw:flex-wrap tw:items-start tw:justify-between tw:gap-3 tw:border-b tw:border-secondary tw:px-5 tw:py-3.5">
                <div>
                  <div className="tw:flex tw:items-center tw:gap-2">
                    <h2 className="tw:text-sm tw:font-semibold tw:text-primary">
                      {HOME_PERSONA_LABELS[role]}
                    </h2>
                    <span
                      className={
                        persona.configured
                          ? 'tw:rounded-full tw:bg-utility-brand-50 tw:px-2 tw:py-0.5 tw:text-xs tw:text-utility-brand-700'
                          : 'tw:rounded-full tw:bg-secondary tw:px-2 tw:py-0.5 tw:text-xs tw:text-tertiary'
                      }>
                      {persona.configured ? 'Arranged' : 'Built-in'}
                    </span>
                  </div>
                  <p className="tw:mt-0.5 tw:max-w-2xl tw:text-xs tw:text-tertiary">
                    {PERSONA_BLURBS[role]}
                    {persona.configured && persona.updatedBy
                      ? ` Arranged by ${persona.updatedBy}.`
                      : ''}
                  </p>
                </div>
                {!open && (
                  <div className="tw:flex tw:items-center tw:gap-2">
                    {persona.configured && (
                      <Button
                        color="tertiary"
                        iconLeading={RefreshCcw01}
                        isDisabled={reset.isPending}
                        onPress={() => {
                          setError(null);
                          reset.mutate(role);
                        }}
                        size="sm">
                        Use the built-in page
                      </Button>
                    )}
                    <Button
                      color="secondary"
                      iconLeading={Edit03}
                      onPress={() => {
                        setError(null);
                        setEditing(role);
                        setDraft(persona.layout);
                      }}
                      size="sm">
                      Arrange
                    </Button>
                  </div>
                )}
              </header>

              {open && draft && (
                <div className="tw:px-5 tw:pt-4">
                  <HomeEditor
                    draft={draft}
                    error={error}
                    governanceReader={governs}
                    heading={`Arranging the ${HOME_PERSONA_LABELS[
                      role
                    ].toLowerCase()} page`}
                    onCancel={() => {
                      setEditing(null);
                      setDraft(null);
                      setError(null);
                    }}
                    onChange={setDraft}
                    onReset={() => reset.mutate(role)}
                    onSave={() => save.mutate({ role, layout: draft })}
                    resetLabel="Use the built-in page"
                    saving={save.isPending || reset.isPending}
                    subheading={
                      'Everyone with this role who has not arranged their own page will' +
                      ' see this. Anyone who has arranged theirs keeps it.'
                    }
                  />
                </div>
              )}

              <div className="tw:px-5 tw:py-4">
                {shown.widgets.length === 0 ? (
                  <p className="tw:rounded-lg tw:border tw:border-dashed tw:border-secondary tw:px-4 tw:py-6 tw:text-center tw:text-sm tw:text-tertiary">
                    This page is empty. Anyone with this role would arrive at
                    nothing.
                  </p>
                ) : (
                  <div
                    className="arak-home-grid"
                    style={gridStyle(shown.preset)}>
                    {Array.from(
                      { length: columnsOf(shown.preset) },
                      (_, column) => (
                        <div
                          className="tw:flex tw:flex-col tw:gap-5"
                          key={column}>
                          {shown.widgets
                            .filter((widget) => widget.column === column)
                            .map((widget) => (
                              <HomeWidgetView key={widget.id} widget={widget} />
                            ))}
                        </div>
                      )
                    )}
                  </div>
                )}
              </div>
            </section>
          );
        })
      )}
    </div>
  );
}
