import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { Sidebar } from './AppShell';
import { arrangeRail, NAV_SECTIONS, sectionsFor, type NavSection } from './navigation';
import type { RailView } from '../api/rail';

const fetchRail = jest.fn();
const saveRail = jest.fn();
const resetRail = jest.fn();
let roles: string[] = [];

jest.mock('../api/rail', () => ({
  ...jest.requireActual('../api/rail'),
  fetchRail: () => fetchRail(),
  saveRail: (...args: unknown[]) => saveRail(...args),
  resetRail: () => resetRail(),
}));

jest.mock('../pages/requests/useRequestNotices', () => ({
  useRequestNotices: () => ({ data: { inboxPending: 0 } }),
  countLabel: (n: number) => String(n),
}));

// PLATFORM_ADMIN holds every role, as the real store has it.
const hasRole = (...wanted: string[]) =>
  roles.includes('PLATFORM_ADMIN') || wanted.some((role) => roles.includes(role));

jest.mock('../auth/authStore', () => ({
  useAuthStore: (selector: (state: unknown) => unknown) => selector({ hasRole }),
}));

function section(href: string): NavSection {
  return NAV_SECTIONS.find((s) => s.href === href) as NavSection;
}

function hrefs(items: { section: NavSection; shown: boolean }[], onlyShown = false) {
  return items.filter((item) => !onlyShown || item.shown).map((item) => item.section.href);
}

describe('arrangeRail', () => {
  const offered = [
    section('/'),
    section('/catalog'),
    section('/query'),
    section('/enforcement'),
    section('/settings'),
  ];

  it('with nothing saved is the default rail, Enforcement left out of it', () => {
    const items = arrangeRail(offered, null);
    expect(hrefs(items)).toEqual(['/', '/catalog', '/query', '/enforcement', '/settings']);
    expect(hrefs(items, true)).toEqual(['/', '/catalog', '/query', '/settings']);
  });

  it('follows the saved order and the saved choices', () => {
    const items = arrangeRail(offered, [
      { href: '/query', shown: true },
      { href: '/enforcement', shown: true },
      { href: '/', shown: false },
      { href: '/catalog', shown: true },
      { href: '/settings', shown: true },
    ]);
    expect(hrefs(items)).toEqual(['/query', '/enforcement', '/', '/catalog', '/settings']);
    expect(hrefs(items, true)).toEqual(['/query', '/enforcement', '/catalog', '/settings']);
  });

  it('drops what this account is not offered, however it got into the list', () => {
    const items = arrangeRail(offered, [
      { href: '/principals', shown: true },
      { href: '/gone', shown: true },
      { href: '/query', shown: true },
      { href: '/query', shown: false },
    ]);
    expect(hrefs(items)).not.toContain('/principals');
    expect(hrefs(items)).not.toContain('/gone');
    expect(items.filter((item) => item.section.href === '/query')).toHaveLength(1);
    expect(items.find((item) => item.section.href === '/query')?.shown).toBe(true);
  });

  it('slots a section it never arranged in after the one it follows by default', () => {
    // Saved before Catalog and Settings were offered to this person.
    const items = arrangeRail(offered, [
      { href: '/query', shown: true },
      { href: '/', shown: true },
      { href: '/enforcement', shown: false },
    ]);
    expect(hrefs(items)).toEqual(['/query', '/', '/catalog', '/enforcement', '/settings']);
    expect(items.find((item) => item.section.href === '/catalog')?.shown).toBe(true);
  });
});

describe('Settings and Enforcement in the rail', () => {
  it('Enforcement is offered to the roles that use it, yet not drawn by default', () => {
    roles = ['POLICY_AUTHOR'];
    const offered = sectionsFor(hasRole).map((s) => s.href);
    expect(offered).toContain('/enforcement');
    expect(offered).toContain('/settings');
    expect(section('/enforcement').defaultShown).toBe(false);
  });

  it('a requester is offered neither', () => {
    roles = ['REQUESTER'];
    const offered = sectionsFor(hasRole).map((s) => s.href);
    expect(offered).not.toContain('/enforcement');
    expect(offered).not.toContain('/settings');
  });

  it('System is no longer a section of its own', () => {
    expect(NAV_SECTIONS.map((s) => s.href)).not.toContain('/system');
  });
});

function renderRail() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={client}>
      <MemoryRouter initialEntries={['/catalog']}>
        <Sidebar activeUrl="/catalog" collapsed={false} />
      </MemoryRouter>
    </QueryClientProvider>
  );
}

function railLinks() {
  const nav = screen.getByRole('navigation', { name: 'Sections' });
  return within(nav)
    .getAllByRole('link')
    .map((link) => link.textContent?.trim());
}

/** A whole saved rail: these first, the rest in default order, some hidden. */
function savedRail(first: string[], hidden: string[] = []) {
  const rest = sectionsFor(hasRole)
    .map((s) => s.href)
    .filter((href) => !first.includes(href));
  return [...first, ...rest].map((href) => ({ href, shown: !hidden.includes(href) }));
}

async function openCustomizer() {
  fireEvent.click(screen.getByRole('button', { name: 'Customize rail' }));
  return screen.findByRole('dialog', { name: 'Customize your rail' });
}

describe('Sidebar', () => {
  beforeEach(() => {
    roles = ['POLICY_AUTHOR'];
    fetchRail.mockReset();
    saveRail.mockReset();
    resetRail.mockReset();
  });

  it('draws the rail this person saved', async () => {
    fetchRail.mockResolvedValue({
      sections: savedRail(['/requests', '/enforcement'], ['/']),
      updatedAt: '2026-09-25T00:00:00Z',
    } satisfies RailView);
    renderRail();

    await waitFor(() => expect(railLinks()[0]).toBe('Requests'));
    expect(railLinks()[1]).toBe('Enforcement');
    expect(railLinks()).not.toContain('Home');
  });

  it('draws the default rail while the preference loads, and if it never does', async () => {
    fetchRail.mockRejectedValue(new Error('down'));
    renderRail();
    expect(railLinks()[0]).toBe('Home');
    expect(railLinks()).not.toContain('Enforcement');
    expect(railLinks()).toContain('Settings');
    await waitFor(() => expect(fetchRail).toHaveBeenCalled());
    expect(railLinks()[0]).toBe('Home');
  });

  it('customizes: pin a section, move others, and save the whole list', async () => {
    fetchRail.mockResolvedValue({ sections: null, updatedAt: null });
    saveRail.mockImplementation(async (sections) => ({
      sections,
      updatedAt: '2026-09-25T00:00:00Z',
    }));
    renderRail();
    await waitFor(() => expect(fetchRail).toHaveBeenCalled());

    const dialog = await openCustomizer();
    const save = within(dialog).getByRole('button', { name: 'Save' });
    expect(save).toBeDisabled();
    // Nothing of their own yet, so nothing to reset.
    expect(within(dialog).getByRole('button', { name: 'Reset to default' })).toBeDisabled();
    expect(within(dialog).getByText('Also in Settings')).toBeInTheDocument();
    expect(within(dialog).getByRole('button', { name: 'Move Home up' })).toBeDisabled();

    fireEvent.click(within(dialog).getByRole('switch', { name: 'Show Enforcement in the rail' }));
    fireEvent.click(within(dialog).getByRole('button', { name: 'Move Dashboard up' }));
    fireEvent.click(within(dialog).getByRole('button', { name: 'Move Requests up' }));
    fireEvent.click(within(dialog).getByRole('switch', { name: 'Show Home in the rail' }));
    expect(save).toBeEnabled();
    fireEvent.click(save);

    await waitFor(() => expect(saveRail).toHaveBeenCalledTimes(1));
    const sent = saveRail.mock.calls[0][0] as { href: string; shown: boolean }[];
    const order = sent.map((entry) => entry.href);
    expect(order.slice(0, 2)).toEqual(['/dashboard', '/']);
    expect(order.indexOf('/requests')).toBe(order.indexOf('/audit') - 1);
    expect(sent.find((entry) => entry.href === '/enforcement')?.shown).toBe(true);
    expect(sent.find((entry) => entry.href === '/')?.shown).toBe(false);
    // Every section this account is offered, not only the visible ones.
    expect(sent).toHaveLength(sectionsFor(hasRole).length);

    await waitFor(() =>
      expect(screen.queryByRole('dialog', { name: 'Customize your rail' })).toBeNull()
    );
    expect(railLinks()[0]).toBe('Dashboard');
    expect(railLinks()).toContain('Enforcement');
    expect(railLinks()).not.toContain('Home');
  });

  it('is drawn the way it always was until somebody picks Compact, and remembers the pick', async () => {
    fetchRail.mockResolvedValue({ sections: null, updatedAt: null });
    saveRail.mockImplementation(async (sections, density) => ({
      sections,
      density,
      updatedAt: '2026-09-26T00:00:00Z',
    }));
    renderRail();
    await waitFor(() => expect(fetchRail).toHaveBeenCalled());
    const nav = screen.getByRole('navigation', { name: 'Sections' });
    expect(nav).toHaveAttribute('data-density', 'comfortable');
    // The full-width button of the original rail, not the footer icon.
    expect(screen.getByRole('button', { name: 'Customize rail' })).toHaveTextContent('Customize rail');

    const dialog = await openCustomizer();
    const comfortable = within(dialog).getByRole('radio', { name: /Comfortable/ });
    const compact = within(dialog).getByRole('radio', { name: /Compact/ });
    expect(comfortable).toHaveAttribute('aria-checked', 'true');
    const save = within(dialog).getByRole('button', { name: 'Save' });
    expect(save).toBeDisabled();
    // The size alone is a change worth saving.
    fireEvent.click(compact);
    expect(compact).toHaveAttribute('aria-checked', 'true');
    expect(save).toBeEnabled();
    fireEvent.click(save);

    await waitFor(() => expect(saveRail).toHaveBeenCalledTimes(1));
    expect(saveRail.mock.calls[0][1]).toBe('compact');
    expect(saveRail.mock.calls[0][0]).toHaveLength(sectionsFor(hasRole).length);
    await waitFor(() => expect(nav).toHaveAttribute('data-density', 'compact'));
    expect(screen.getByRole('button', { name: 'Customize rail' })).toHaveTextContent('');
  });

  it('reads a saved Compact rail, and an unknown size as the default', async () => {
    fetchRail.mockResolvedValue({ sections: savedRail(['/query']), density: 'compact', updatedAt: 'x' });
    const { unmount } = renderRail();
    const nav = screen.getByRole('navigation', { name: 'Sections' });
    await waitFor(() => expect(nav).toHaveAttribute('data-density', 'compact'));
    unmount();

    fetchRail.mockResolvedValue({ sections: null, density: 'huge', updatedAt: null });
    renderRail();
    await waitFor(() => expect(fetchRail).toHaveBeenCalledTimes(2));
    expect(screen.getByRole('navigation', { name: 'Sections' })).toHaveAttribute(
      'data-density',
      'comfortable'
    );
  });

  it('will not save a rail with nothing in it', async () => {
    fetchRail.mockResolvedValue({ sections: null, updatedAt: null });
    renderRail();
    const dialog = await openCustomizer();
    for (const toggle of within(dialog).getAllByRole('switch')) {
      if ((toggle as HTMLInputElement).checked) fireEvent.click(toggle);
    }
    expect(within(dialog).getByText('Keep at least one section in the rail.')).toBeInTheDocument();
    expect(within(dialog).getByRole('button', { name: 'Save' })).toBeDisabled();
  });

  it('cancel keeps the rail as it was, and reset forgets an arrangement', async () => {
    fetchRail.mockResolvedValue({
      sections: savedRail(['/query']),
      updatedAt: '2026-09-25T00:00:00Z',
    });
    resetRail.mockResolvedValue({ sections: null, updatedAt: null });
    renderRail();
    await waitFor(() => expect(railLinks()[0]).toBe('Query'));

    let dialog = await openCustomizer();
    fireEvent.click(within(dialog).getByRole('button', { name: 'Move Query down' }));
    fireEvent.click(within(dialog).getByRole('button', { name: 'Cancel' }));
    await waitFor(() =>
      expect(screen.queryByRole('dialog', { name: 'Customize your rail' })).toBeNull()
    );
    expect(saveRail).not.toHaveBeenCalled();
    expect(railLinks()[0]).toBe('Query');

    dialog = await openCustomizer();
    // Reopened from the rail as it is, not from the abandoned draft.
    expect(within(dialog).getAllByRole('listitem')[0]).toHaveTextContent('Query');
    fireEvent.click(within(dialog).getByRole('button', { name: 'Reset to default' }));
    await waitFor(() => expect(resetRail).toHaveBeenCalled());
    await waitFor(() => expect(railLinks()[0]).toBe('Home'));
  });

  it('says why a save failed and keeps the window open', async () => {
    fetchRail.mockResolvedValue({ sections: null, updatedAt: null });
    saveRail.mockRejectedValue(new Error('network'));
    renderRail();
    const dialog = await openCustomizer();
    fireEvent.click(within(dialog).getByRole('switch', { name: 'Show Enforcement in the rail' }));
    fireEvent.click(within(dialog).getByRole('button', { name: 'Save' }));
    expect(await within(dialog).findByRole('alert')).toBeInTheDocument();
    expect(screen.getByRole('dialog', { name: 'Customize your rail' })).toBeInTheDocument();
  });
});
