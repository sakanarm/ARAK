import {
  useEffect,
  useMemo,
  useRef,
  useState,
  type FC,
  type FormEvent,
  type KeyboardEvent as KeyboardEvent2,
  type ReactNode,
} from 'react';
import { useNavigate } from 'react-router-dom';
import { useQuery } from '@tanstack/react-query';
import {
  Bell01,
  BookClosed,
  BookOpen01,
  Bookmark,
  ChevronDown,
  Columns03,
  CornerDownLeft,
  Cube01,
  Database01,
  Globe01,
  HelpCircle,
  LayoutLeft,
  LayoutRight,
  Loading02,
  LogOut01,
  Plus,
  RefreshCcw01,
  SearchLg,
  Server01,
  ShieldTick,
  Table,
  Tag01,
  XClose,
} from '@untitledui/icons';
import {
  Button as AriaButton,
  Dialog as AriaDialog,
  DialogTrigger as AriaDialogTrigger,
  Modal as AriaModal,
  ModalOverlay as AriaModalOverlay,
} from 'react-aria-components';
import { Avatar } from '@openmetadata/ui-core-components/components/base/avatar/avatar';
import { Badge } from '@openmetadata/ui-core-components/components/base/badges/badges';
import { Button } from '@openmetadata/ui-core-components/components/base/buttons/button';
import { Dropdown } from '@openmetadata/ui-core-components/components/base/dropdown/dropdown';
import { Tooltip } from '@openmetadata/ui-core-components/components/base/tooltip/tooltip';
import { fetchVocabulary, type GovernanceValue } from '../api/governance';
import {
  hitHref,
  KIND_GROUPS,
  search,
  type SearchHit,
  type SearchKind,
} from '../api/search';
import { fetchSyncStatus } from '../api/system';
import { AUTH_SPLASH_MS, useAuthSplash } from '../auth/AuthSplash';
import { useAuthStore } from '../auth/authStore';
import { plainText } from '../lib/text';
import mark from '../assets/arak-mark.png';

/**
 * The header bar, laid out as OpenMetadata 2.0 lays out its own.
 *
 * Left to right: the control that hides the rail, one search that reaches the
 * whole estate with a scope inside it, and the domain the console is looking
 * at. Then, right-aligned, the state of the cache, what you can start, and who
 * you are signed in as.
 *
 * Every control goes somewhere real. A header of decorative icons is worse than
 * a plain one, because it teaches people that clicking things in this product
 * does nothing — so there is no bell here until something rings it.
 */
export default function TopNav({
  drawer,
  collapsed,
  onToggleSidebar,
}: {
  drawer: ReactNode;
  collapsed: boolean;
  onToggleSidebar: () => void;
}) {
  return (
    <header className="tw:sticky tw:top-0 tw:z-50 tw:flex tw:h-16 tw:shrink-0 tw:items-center tw:gap-2 tw:border-b tw:border-secondary tw:bg-primary tw:px-3 tw:sm:gap-3 tw:sm:px-4">
      <MobileMenu>{drawer}</MobileMenu>

      <Brand collapsed={collapsed} />

      {/*
        A react-aria Button rather than a plain one, because the tooltip is a
        TooltipTrigger and only passes its hover and focus handling to a trigger
        that accepts them — a bare <button> would show nothing.
      */}
      <Tooltip arrow placement="right" title={collapsed ? 'Expand' : 'Collapse'}>
        <AriaButton
          aria-label={collapsed ? 'Expand the sidebar' : 'Collapse the sidebar'}
          aria-pressed={!collapsed}
          className="tw:hidden tw:cursor-pointer tw:rounded-lg tw:p-2 tw:text-fg-quaternary tw:outline-focus-ring tw:transition tw:hover:bg-secondary tw:hover:text-fg-secondary tw:focus-visible:outline-2 tw:lg:block"
          onPress={onToggleSidebar}>
          {/* The divider flips to the side the rail is about to be on. */}
          {collapsed ? (
            <LayoutRight className="tw:size-5" />
          ) : (
            <LayoutLeft className="tw:size-5" />
          )}
        </AriaButton>
      </Tooltip>

      <GlobalSearch />

      <DomainPicker />

      <div className="tw:ml-auto tw:flex tw:items-center tw:gap-1 tw:sm:gap-2">
        <CreateMenu />
        <Notifications />
        <HelpMenu />
        <AccountMenu />
      </div>
    </header>
  );
}

/**
 * The mark and the name, at the top left where a product's name belongs.
 *
 * In the header rather than in the rail, because the rail can be collapsed and
 * a product whose name disappears when you widen the table you are reading is a
 * product people cannot cite in a ticket.
 */
function Brand({ collapsed }: { collapsed: boolean }) {
  const navigate = useNavigate();

  return (
    <button
      aria-label="ARAK home"
      // The block is exactly as wide as the rail beneath it and narrows with
      // it, on the same 400ms, so the logo and the toggle travel together
      // instead of the toggle sliding out from under a logo that stayed put.
      className={`tw:flex tw:shrink-0 tw:cursor-pointer tw:items-center tw:gap-3 tw:rounded-lg tw:px-1 tw:py-1 tw:text-left tw:outline-focus-ring tw:transition-[width,background-color] tw:duration-400 tw:ease-in-out tw:hover:bg-secondary tw:focus-visible:outline-2 ${
        collapsed ? 'tw:lg:w-14' : 'tw:lg:w-66'
      }`}
      onClick={() => navigate('/')}
      type="button">
      <img alt="" aria-hidden className="tw:size-10 tw:shrink-0" src={mark} />
      <span
        className={
          collapsed ? 'tw:hidden tw:sm:block tw:lg:hidden' : 'tw:hidden tw:sm:block'
        }>
        <span className="tw:block tw:text-lg tw:font-semibold tw:leading-6 tw:text-primary">
          ARAK
        </span>
        <span className="tw:block tw:text-xs tw:leading-4 tw:text-tertiary tw:whitespace-nowrap">
          Data access control
        </span>
      </span>
    </button>
  );
}

/** How long after the last keystroke the request goes out. */
const SEARCH_DEBOUNCE_MS = 200;

/** Below this the term matches most of the estate, so there is nothing to show. */
const MIN_SEARCH_LENGTH = 2;

const KIND_ICONS: Record<SearchKind, FC<{ className?: string }>> = {
  asset: Table,
  column: Columns03,
  tag: Tag01,
  classification: Bookmark,
  term: BookOpen01,
  glossary: BookClosed,
  domain: Globe01,
  dataProduct: Cube01,
  policy: ShieldTick,
};

/**
 * One search for the whole estate — every kind of thing, not only assets.
 *
 * A person looking for `citizen_id` does not know whether it is a column, a
 * tag or a glossary term, and asking them to pick the right screen first is
 * asking them to know the answer before they search. So this queries all of
 * them at once and groups the results by what they turned out to be.
 *
 * Results open here rather than on the catalog page because most searches end
 * in one click — "take me to that table" — and a round trip through a filtered
 * list to click the only row in it is a page nobody wanted. Enter still goes to
 * the catalog, for the searches that really are about browsing a set.
 */
function GlobalSearch() {
  const navigate = useNavigate();
  const input = useRef<HTMLInputElement>(null);
  const box = useRef<HTMLDivElement>(null);
  const [term, setTerm] = useState('');
  const [debounced, setDebounced] = useState('');
  const [open, setOpen] = useState(false);
  const [active, setActive] = useState(0);

  // Debounced, so that typing a table name is one request and not eleven.
  useEffect(() => {
    const timer = window.setTimeout(
      () => setDebounced(term.trim()),
      SEARCH_DEBOUNCE_MS
    );
    return () => window.clearTimeout(timer);
  }, [term]);

  const enabled = debounced.length >= MIN_SEARCH_LENGTH;

  const { data, isFetching } = useQuery({
    queryKey: ['search', debounced],
    queryFn: () => search(debounced, 20),
    enabled,
    // Results are a snapshot of the catalog cache, which moves on sync, not on
    // the second. Half a minute of reuse makes retyping a term feel instant.
    staleTime: 30 * 1000,
    placeholderData: (previous) => previous,
    retry: false,
  });

  const hits = useMemo(
    () => (enabled ? (data?.items ?? []) : []),
    [data, enabled]
  );

  // Grouped for display, but kept as one flat list as well: the arrow keys walk
  // the results the way they look, and that is one sequence, not nine.
  const groups = useMemo(
    () =>
      KIND_GROUPS.map((group) => ({
        ...group,
        items: hits.filter((hit) => hit.kind === group.kind),
      })).filter((group) => group.items.length > 0),
    [hits]
  );

  const ordered = useMemo(
    () => groups.flatMap((group) => group.items),
    [groups]
  );

  useEffect(() => setActive(0), [debounced]);

  // "/" focuses search, the shortcut every catalog uses — but not while the
  // person is already typing into something else.
  useEffect(() => {
    function onKeyDown(event: KeyboardEvent) {
      const target = event.target as HTMLElement | null;
      const typing =
        target instanceof HTMLInputElement ||
        target instanceof HTMLTextAreaElement ||
        target?.isContentEditable === true;

      const slash = event.key === '/' && !typing;
      const palette =
        event.key.toLowerCase() === 'k' && (event.metaKey || event.ctrlKey);

      if (slash || palette) {
        event.preventDefault();
        input.current?.focus();
      }
    }

    window.addEventListener('keydown', onKeyDown);
    return () => window.removeEventListener('keydown', onKeyDown);
  }, []);

  // Clicking anywhere else closes the list. Bound to mousedown rather than
  // click so the list is gone before the click lands, which stops a result
  // from flashing back open under the cursor.
  useEffect(() => {
    if (!open) {
      return;
    }
    function onPointerDown(event: MouseEvent) {
      if (!box.current?.contains(event.target as Node)) {
        setOpen(false);
      }
    }
    document.addEventListener('mousedown', onPointerDown);
    return () => document.removeEventListener('mousedown', onPointerDown);
  }, [open]);

  function go(hit: SearchHit) {
    setOpen(false);
    input.current?.blur();
    navigate(hitHref(hit));
  }

  function submit(event: FormEvent) {
    event.preventDefault();
    const chosen = ordered[active];
    if (open && chosen) {
      go(chosen);
      return;
    }
    const query = term.trim() ? `?q=${encodeURIComponent(term.trim())}` : '';
    setOpen(false);
    navigate(`/catalog${query}`);
    input.current?.blur();
  }

  function onKeyDown(event: KeyboardEvent2) {
    if (event.key === 'Escape') {
      setOpen(false);
      input.current?.blur();
      return;
    }
    if (event.key === 'ArrowDown' || event.key === 'ArrowUp') {
      if (ordered.length === 0) {
        return;
      }
      event.preventDefault();
      setOpen(true);
      const step = event.key === 'ArrowDown' ? 1 : -1;
      // Wraps, so holding one arrow key cannot strand the selection at an end.
      setActive((index) => (index + step + ordered.length) % ordered.length);
    }
  }

  const showPanel = open && enabled;

  return (
    <div className="tw:relative tw:hidden tw:w-full tw:max-w-2xl tw:md:block tw:lg:ml-4" ref={box}>
      <form onSubmit={submit} role="search">
        <div className="tw:flex tw:h-10 tw:items-center tw:gap-1 tw:rounded-lg tw:bg-secondary tw:pr-1 tw:pl-4 tw:outline-1 tw:-outline-offset-1 tw:outline-transparent tw:transition tw:focus-within:bg-primary tw:focus-within:outline-brand">
          <input
            aria-label="Search assets, tags, terms and domains"
            className="tw:min-w-0 tw:flex-1 tw:bg-transparent tw:text-sm tw:text-primary tw:outline-hidden tw:placeholder:text-placeholder"
            onChange={(event) => {
              setTerm(event.target.value);
              setOpen(true);
            }}
            onFocus={() => setOpen(true)}
            onKeyDown={onKeyDown}
            placeholder="Search assets, columns, tags, terms, domains"
            ref={input}
            type="text"
            value={term}
          />

          {/*
            The spinner replaces the magnifier rather than sitting beside it,
            so the field's width does not twitch on every keystroke.
          */}
          {isFetching && enabled ? (
            <span
              aria-label="Searching"
              className="tw:flex tw:size-8 tw:shrink-0 tw:items-center tw:justify-center"
              role="status">
              <Loading02 className="tw:size-4.5 tw:animate-spin tw:text-fg-quaternary" />
            </span>
          ) : (
            <button
              aria-label="Search"
              className="tw:flex tw:size-8 tw:shrink-0 tw:cursor-pointer tw:items-center tw:justify-center tw:rounded-md tw:text-fg-quaternary tw:outline-focus-ring tw:hover:bg-primary_hover tw:hover:text-fg-secondary tw:focus-visible:outline-2"
              type="submit">
              <SearchLg className="tw:size-4.5" />
            </button>
          )}
        </div>
      </form>

      {showPanel && (
        <div className="tw:absolute tw:top-12 tw:right-0 tw:left-0 tw:z-50 tw:max-h-[70vh] tw:overflow-y-auto tw:rounded-xl tw:border tw:border-secondary tw:bg-primary tw:py-1.5 tw:shadow-lg">
          {ordered.length === 0 ? (
            <p className="tw:px-4 tw:py-6 tw:text-center tw:text-sm tw:text-tertiary">
              {isFetching
                ? 'Searching…'
                : `Nothing matches “${debounced}”.`}
            </p>
          ) : (
            <>
              {groups.map((group) => (
                <div key={group.kind}>
                  <p className="tw:px-4 tw:pt-2 tw:pb-1 tw:text-xs tw:font-semibold tw:tracking-wide tw:text-quaternary tw:uppercase">
                    {group.label}
                  </p>
                  {group.items.map((hit) => (
                    <SearchResult
                      active={ordered[active] === hit}
                      hit={hit}
                      key={`${hit.kind}:${hit.fqn}:${hit.id ?? ''}`}
                      onHover={() => setActive(ordered.indexOf(hit))}
                      onSelect={() => go(hit)}
                    />
                  ))}
                </div>
              ))}

              <div className="tw:mt-1.5 tw:border-t tw:border-secondary tw:px-4 tw:pt-2 tw:pb-1 tw:text-xs tw:text-quaternary">
                <CornerDownLeft aria-hidden className="tw:mr-1.5 tw:inline tw:size-3.5" />
                to open · Esc to close
              </div>
            </>
          )}
        </div>
      )}
    </div>
  );
}

/** One row in the search panel. */
function SearchResult({
  hit,
  active,
  onSelect,
  onHover,
}: {
  hit: SearchHit;
  active: boolean;
  onSelect: () => void;
  onHover: () => void;
}) {
  const Icon = KIND_ICONS[hit.kind];
  const subtitle = plainText(hit.description) || hit.fqn || '';

  return (
    <button
      className={`tw:flex tw:w-full tw:cursor-pointer tw:items-center tw:gap-3 tw:px-4 tw:py-2 tw:text-left ${
        active ? 'tw:bg-secondary' : ''
      }`}
      // mousedown, not click: the input's blur would otherwise close the panel
      // before the click could land on it.
      onMouseDown={(event) => {
        event.preventDefault();
        onSelect();
      }}
      onMouseEnter={onHover}
      type="button">
      <Icon aria-hidden className="tw:size-4.5 tw:shrink-0 tw:text-fg-quaternary" />

      <span className="tw:min-w-0 tw:flex-1">
        <span className="tw:block tw:truncate tw:text-sm tw:font-medium tw:text-primary">
          {hit.displayName || hit.name}
        </span>
        {subtitle && (
          <span className="tw:block tw:truncate tw:text-xs tw:text-tertiary">
            {subtitle}
          </span>
        )}
      </span>

      {hit.subtype && (
        <Badge color="gray" size="sm" type="pill-color">
          {hit.subtype.toLowerCase()}
        </Badge>
      )}
      {hit.assets > 0 && (
        <span className="tw:shrink-0 tw:text-xs tw:text-quaternary">
          {hit.assets} assets
        </span>
      )}
    </button>
  );
}

/**
 * Which domain the console is looking at.
 *
 * A domain is the unit a data owner is responsible for, so it is the filter
 * most often applied and the one worth keeping in the header rather than three
 * clicks into the catalog. Choosing one lands on the catalog already filtered
 * by that domain — including its sub-domains, which is what `domains` means as
 * a facet (FR-2A.2).
 */
function DomainPicker() {
  const navigate = useNavigate();
  const [chosen, setChosen] = useState<string>('');

  const { data: vocabulary } = useQuery({
    queryKey: ['vocabulary'],
    queryFn: fetchVocabulary,
    staleTime: 5 * 60 * 1000,
    retry: false,
  });

  const domains = useMemo(
    () => flatten(vocabulary?.domains ?? []),
    [vocabulary]
  );

  if (domains.length === 0) {
    return null;
  }

  const current = domains.find((domain) => domain.fqn === chosen);

  return (
    <Dropdown.Root>
      <AriaButton
        aria-label="Filter by domain"
        className="tw:hidden tw:h-10 tw:cursor-pointer tw:items-center tw:gap-2 tw:rounded-lg tw:border tw:border-secondary tw:bg-primary tw:px-3 tw:text-sm tw:font-medium tw:text-secondary tw:outline-focus-ring tw:transition tw:hover:bg-primary_hover tw:focus-visible:outline-2 tw:lg:flex">
        <Globe01 className="tw:size-4 tw:text-fg-quaternary" />
        <span className="tw:max-w-40 tw:truncate">
          {current ? current.label : 'All Domains'}
        </span>
        <ChevronDown className="tw:size-3.5 tw:text-fg-quaternary" />
      </AriaButton>

      <Dropdown.Popover className="tw:max-h-96! tw:w-72 tw:overflow-y-auto">
        <Dropdown.Menu selectionMode="none">
          <Dropdown.Item
            icon={Globe01}
            label="All Domains"
            onAction={() => {
              setChosen('');
              navigate('/catalog');
            }}
          />
          <Dropdown.Separator />
          {domains.map((domain) => (
            <Dropdown.Item
              addon={domain.assets > 0 ? String(domain.assets) : undefined}
              id={domain.fqn}
              key={domain.fqn}
              label={domain.label}
              onAction={() => {
                setChosen(domain.fqn);
                navigate(
                  `/catalog?facet=${encodeURIComponent(`domains:${domain.fqn}`)}`
                );
              }}
            />
          ))}
        </Dropdown.Menu>
      </Dropdown.Popover>
    </Dropdown.Root>
  );
}

/** Depth-first, with sub-domains indented, because the tree is the meaning. */
function flatten(
  values: GovernanceValue[],
  depth = 0
): { fqn: string; label: string; assets: number }[] {
  return values.flatMap((value) => [
    {
      fqn: value.fqn,
      label: `${'  '.repeat(depth)}${value.displayName || value.name}`,
      assets: value.assets,
    },
    ...flatten(value.children ?? [], depth + 1),
  ]);
}

/**
 * What needs somebody's attention.
 *
 * The bell only rings for things this console actually knows are wrong. The
 * first of those is the age of the metadata cache: every screen in the product
 * is a view of it, so a crawl that failed or never ran silently makes every
 * other screen a lie. Admin-only, because the endpoint behind it is.
 *
 * A bell that is always empty trains people to stop looking at it, so there is
 * no badge unless there is something to read.
 */
function Notifications() {
  const navigate = useNavigate();
  const isAdmin = useAuthStore((state) => state.hasRole('PLATFORM_ADMIN'));

  const { data } = useQuery({
    queryKey: ['sync-status'],
    queryFn: fetchSyncStatus,
    enabled: isAdmin,
    refetchInterval: 60_000,
    retry: false,
  });

  const alerts: { id: string; label: string; detail: string }[] = [];

  if (data?.status === 'FAILED') {
    alerts.push({
      id: 'sync-failed',
      label: 'The last OpenMetadata crawl failed',
      detail: data.lastError ?? 'Open the system page for the error.',
    });
  } else if (data?.status === 'NEVER_RUN') {
    alerts.push({
      id: 'never-synced',
      label: 'The catalog has never been crawled',
      detail: 'Nothing is catalogued yet, so no policy can bind to an asset.',
    });
  }

  return (
    <Dropdown.Root>
      <AriaButton
        aria-label={
          alerts.length > 0
            ? `Notifications, ${alerts.length} needing attention`
            : 'Notifications'
        }
        className="tw:relative tw:flex tw:size-10 tw:cursor-pointer tw:items-center tw:justify-center tw:rounded-lg tw:text-fg-quaternary tw:outline-focus-ring tw:transition tw:hover:bg-secondary tw:hover:text-fg-secondary tw:focus-visible:outline-2">
        <Bell01 className="tw:size-5" />
        {alerts.length > 0 && (
          <span
            aria-hidden
            className="tw:absolute tw:top-2 tw:right-2.5 tw:size-2 tw:rounded-full tw:bg-fg-error-primary tw:ring-2 tw:ring-bg-primary"
          />
        )}
      </AriaButton>

      <Dropdown.Popover className="tw:w-80">
        <div className="tw:border-b tw:border-secondary tw:px-4 tw:py-3">
          <p className="tw:text-sm tw:font-semibold tw:text-primary">
            Notifications
          </p>
          <p className="tw:text-xs tw:text-tertiary">
            {isAdmin
              ? `Metadata cache · crawled ${shortAge(
                  data?.lastFullCrawlAt ?? data?.updatedAt
                )}`
              : 'Only a platform admin sees the state of the metadata cache.'}
          </p>
        </div>

        {alerts.length === 0 ? (
          <p className="tw:px-4 tw:py-6 tw:text-center tw:text-sm tw:text-tertiary">
            Nothing needs your attention.
          </p>
        ) : (
          <Dropdown.Menu selectionMode="none">
            {alerts.map((alert) => (
              <Dropdown.Item
                icon={RefreshCcw01}
                id={alert.id}
                key={alert.id}
                label={alert.label}
                onAction={() => navigate('/settings/openmetadata')}
              />
            ))}
          </Dropdown.Menu>
        )}
      </Dropdown.Popover>
    </Dropdown.Root>
  );
}

/** Where the answers are, for a product whose rules are not self-evident. */
function HelpMenu() {
  const navigate = useNavigate();

  return (
    <Dropdown.Root>
      <AriaButton
        aria-label="Help"
        className="tw:flex tw:size-10 tw:cursor-pointer tw:items-center tw:justify-center tw:rounded-lg tw:text-fg-quaternary tw:outline-focus-ring tw:transition tw:hover:bg-secondary tw:hover:text-fg-secondary tw:focus-visible:outline-2">
        <HelpCircle className="tw:size-5" />
      </AriaButton>

      <Dropdown.Popover className="tw:w-72">
        <Dropdown.Menu selectionMode="none">
          <Dropdown.Item
            addon="/"
            icon={SearchLg}
            label="Search the catalog"
            onAction={() => navigate('/catalog')}
          />
          <Dropdown.Item
            icon={ShieldTick}
            label="How policies compose"
            onAction={() => navigate('/policies')}
          />
          <Dropdown.Item
            icon={Server01}
            label="Connection and sync status"
            onAction={() => navigate('/settings/openmetadata')}
          />
        </Dropdown.Menu>
        <p className="tw:border-t tw:border-secondary tw:px-4 tw:py-3 tw:text-xs tw:text-tertiary">
          Strictest wins: a policy lower down the hierarchy can tighten what a
          global one allows, never loosen it.
        </p>
      </Dropdown.Popover>
    </Dropdown.Root>
  );
}

/**
 * The things somebody starts from a blank page.
 *
 * The kind of policy is chosen here rather than inside the builder, because a
 * subscription policy ("who may read this table") and a data policy ("what do
 * they see in it") are different jobs, and a dropdown in step one of a form is
 * where that distinction goes to be missed.
 */
function CreateMenu() {
  const navigate = useNavigate();
  const canAuthor = useAuthStore((state) =>
    state.hasRole('PLATFORM_ADMIN', 'POLICY_AUTHOR', 'DATA_OWNER')
  );

  if (!canAuthor) {
    return null;
  }

  return (
    <Dropdown.Root>
      <Button color="primary" iconLeading={Plus} size="sm">
        <span className="tw:hidden tw:sm:inline">Create</span>
      </Button>

      <Dropdown.Popover>
        <Dropdown.Menu selectionMode="none">
          <Dropdown.Item
            icon={ShieldTick}
            label="Subscription policy"
            onAction={() => navigate('/policies/new?kind=SUBSCRIPTION')}
          />
          <Dropdown.Item
            icon={Database01}
            label="Data policy"
            onAction={() => navigate('/policies/new?kind=DATA')}
          />
          <Dropdown.Separator />
          <Dropdown.Item
            icon={Server01}
            label="Register a source"
            onAction={() => navigate('/sources')}
          />
        </Dropdown.Menu>
      </Dropdown.Popover>
    </Dropdown.Root>
  );
}

/** Who is signed in, the roles that decide what they may author, and the way out. */
function AccountMenu() {
  const navigate = useNavigate();
  const user = useAuthStore((state) => state.user);
  const signOut = useAuthStore((state) => state.signOut);
  const splash = useAuthSplash();

  // The curtain goes up first and the session is dropped behind it. Dropping
  // it first would unmount this menu, the shell and every page at once, and
  // the login screen would arrive before anyone saw why.
  function endSession() {
    splash.show('signing-out');
    window.setTimeout(() => {
      signOut();
      splash.hide();
    }, AUTH_SPLASH_MS);
  }

  const name = user?.displayName || user?.username || 'Signed in';
  const roles = user?.roles ?? [];
  // The strongest role, not all of them: the header has room for one line, and
  // the full list is one click away inside the menu.
  const headline = roles.length > 0 ? humaniseRole(roles[0]) : 'No role';

  return (
    <Dropdown.Root>
      <AriaButton
        aria-label="Account"
        className="tw:flex tw:cursor-pointer tw:items-center tw:gap-2 tw:rounded-lg tw:py-1 tw:pr-1 tw:pl-1.5 tw:outline-focus-ring tw:transition tw:hover:bg-secondary tw:focus-visible:outline-2">
        <Avatar initials={initialsOf(name)} size="sm" />
        <span className="tw:hidden tw:text-left tw:lg:block">
          <span className="tw:block tw:max-w-36 tw:truncate tw:text-sm tw:font-semibold tw:leading-4 tw:text-primary">
            {name}
          </span>
          <span className="tw:block tw:max-w-36 tw:truncate tw:text-xs tw:leading-4 tw:text-tertiary">
            {headline}
          </span>
        </span>
        <ChevronDown className="tw:size-4 tw:shrink-0 tw:text-fg-quaternary" />
      </AriaButton>

      <Dropdown.Popover>
        <div className="tw:border-b tw:border-secondary tw:px-4 tw:py-3">
          <p className="tw:truncate tw:text-sm tw:font-semibold tw:text-primary">
            {name}
          </p>
          <p className="tw:truncate tw:text-xs tw:text-tertiary">
            {user?.email || user?.username}
          </p>
          {roles.length > 0 && (
            <div className="tw:mt-2 tw:flex tw:flex-wrap tw:gap-1">
              {roles.map((role) => (
                <Badge color="gray" key={role} size="sm" type="pill-color">
                  {humaniseRole(role)}
                </Badge>
              ))}
            </div>
          )}
        </div>

        <Dropdown.Menu selectionMode="none">
          <Dropdown.Item
            icon={Server01}
            label="System status"
            onAction={() => navigate('/system')}
          />
          <Dropdown.Item
            icon={Database01}
            label="Sources"
            onAction={() => navigate('/sources')}
          />
          <Dropdown.Separator />
          <Dropdown.Item icon={LogOut01} label="Sign out" onAction={endSession} />
        </Dropdown.Menu>
      </Dropdown.Popover>
    </Dropdown.Root>
  );
}

/** The drawer that carries the sidebar on a narrow screen. */
function MobileMenu({ children }: { children: ReactNode }) {
  return (
    <AriaDialogTrigger>
      <AriaButton
        aria-label="Open navigation menu"
        className="tw:flex tw:cursor-pointer tw:items-center tw:justify-center tw:rounded-lg tw:p-2 tw:text-fg-secondary tw:outline-focus-ring tw:hover:bg-secondary tw:focus-visible:outline-2 tw:lg:hidden">
        <LayoutLeft className="tw:size-5" />
      </AriaButton>

      <AriaModalOverlay
        className="tw:fixed tw:inset-0 tw:z-60 tw:cursor-pointer tw:bg-overlay/70 tw:pr-16 tw:backdrop-blur-md tw:lg:hidden"
        isDismissable>
        {({ state }) => (
          <>
            <AriaButton
              aria-label="Close navigation menu"
              className="tw:fixed tw:top-3 tw:right-2 tw:flex tw:cursor-pointer tw:items-center tw:justify-center tw:rounded-lg tw:p-2 tw:text-fg-white/70 tw:outline-focus-ring tw:hover:bg-white/10 tw:focus-visible:outline-2"
              onPress={() => state.close()}>
              <XClose className="tw:size-6" />
            </AriaButton>

            <AriaModal className="tw:w-full tw:cursor-auto">
              <AriaDialog className="tw:h-dvh tw:outline-hidden">
                {children}
              </AriaDialog>
            </AriaModal>
          </>
        )}
      </AriaModalOverlay>
    </AriaDialogTrigger>
  );
}

/** "3h ago", "2d ago" — the header has room for an age, not for a sentence. */
function shortAge(iso: string | null | undefined): string {
  if (!iso) {
    return 'never';
  }
  const then = new Date(iso).getTime();
  if (Number.isNaN(then)) {
    return 'never';
  }
  const minutes = Math.round((Date.now() - then) / 60_000);
  if (minutes < 1) {
    return 'just now';
  }
  if (minutes < 60) {
    return `${minutes}m ago`;
  }
  const hours = Math.round(minutes / 60);
  if (hours < 24) {
    return `${hours}h ago`;
  }
  return `${Math.round(hours / 24)}d ago`;
}

/** Two letters at most, so a long display name does not overflow the circle. */
export function initialsOf(name: string): string {
  const parts = name.trim().split(/\s+/).filter(Boolean);
  if (parts.length === 0) {
    return '?';
  }
  if (parts.length === 1) {
    return parts[0].slice(0, 2).toUpperCase();
  }
  return (parts[0][0] + parts[parts.length - 1][0]).toUpperCase();
}

export function humaniseRole(role: string): string {
  return role
    .toLowerCase()
    .split('_')
    .map((word) => word.charAt(0).toUpperCase() + word.slice(1))
    .join(' ');
}
