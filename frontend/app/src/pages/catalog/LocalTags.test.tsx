import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { LocalTagControl, choices } from './LocalTags';
import type { GovernanceValue } from '../../api/governance';

const fetchLocalTags = jest.fn();
const addLocalTag = jest.fn();
const removeLocalTag = jest.fn();
const fetchVocabulary = jest.fn();

jest.mock('../../api/client', () => ({
  apiErrorMessage: (error: unknown, fallback: string) =>
    (error as { message?: string })?.message ?? fallback,
}));
jest.mock('../../api/localTags', () => ({
  fetchLocalTags: (...args: unknown[]) => fetchLocalTags(...args),
  addLocalTag: (...args: unknown[]) => addLocalTag(...args),
  removeLocalTag: (...args: unknown[]) => removeLocalTag(...args),
}));
jest.mock('../../api/governance', () => ({
  fetchVocabulary: (...args: unknown[]) => fetchVocabulary(...args),
}));

const TABLE = 'demo-pg.salesdb.sales.customer';
const EMAIL = `${TABLE}.email`;

function value(fqn: string, overrides: Partial<GovernanceValue> = {}): GovernanceValue {
  const parts = fqn.split('.');
  return {
    fqn,
    name: parts[parts.length - 1],
    parentFqn: parts.length > 1 ? parts.slice(0, -1).join('.') : null,
    displayName: null,
    description: null,
    depth: parts.length - 1,
    provenance: 'openmetadata',
    disabled: false,
    mutuallyExclusive: false,
    assets: 0,
    directAssets: 0,
    policies: 0,
    children: [],
    ...overrides,
  };
}

const PII = value('PII', {
  children: [value('PII.Sensitive'), value('PII.NonSensitive'), value('PII.Retired', { disabled: true })],
});
const TIER = value('Tier', {
  mutuallyExclusive: true,
  children: [value('Tier.Tier1'), value('Tier.Tier2')],
});
const OFF = value('Old', { disabled: true, children: [value('Old.Thing')] });

function renderControl(carried: string[] = []) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  render(
    <QueryClientProvider client={client}>
      <LocalTagControl assetFqn={TABLE} carried={carried} targetFqn={EMAIL} />
    </QueryClientProvider>
  );
}

beforeEach(() => {
  jest.resetAllMocks();
  fetchVocabulary.mockResolvedValue({ classifications: [PII, TIER, OFF] });
});

describe('choices', () => {
  it('offers enabled tags the target does not carry, and nothing more from a one-only classification it has', () => {
    const offered = choices([PII, TIER, OFF], ['PII.Sensitive', 'Tier.Tier1']);
    expect(offered.map(([c, tags]) => [c.fqn, tags.map((t) => t.fqn)])).toEqual([
      ['PII', ['PII.NonSensitive']],
    ]);
  });
});

describe('LocalTagControl', () => {
  it('shows nothing to somebody who does not govern the table', async () => {
    fetchLocalTags.mockResolvedValue({ canEdit: false, tags: [] });
    renderControl();

    await waitFor(() => expect(fetchLocalTags).toHaveBeenCalledWith(TABLE));
    expect(screen.queryByRole('button', { name: 'Edit tags' })).toBeNull();
  });

  it('attaches a tag with a reason, and not without one', async () => {
    fetchLocalTags.mockResolvedValue({ canEdit: true, tags: [] });
    addLocalTag.mockResolvedValue({});
    renderControl(['PII.Sensitive']);

    fireEvent.click(await screen.findByRole('button', { name: 'Edit tags' }));
    const picker = screen.getByRole('combobox', { name: 'Tag to attach' });
    await within(picker).findByRole('option', { name: 'PII.NonSensitive' });
    expect(within(picker).queryByRole('option', { name: 'PII.Sensitive' })).toBeNull();
    expect(within(picker).queryByRole('option', { name: 'PII.Retired' })).toBeNull();

    fireEvent.change(picker, { target: { value: 'PII.NonSensitive' } });
    const attach = screen.getByRole('button', { name: 'Attach tag' });
    expect(attach).toBeDisabled();

    fireEvent.change(screen.getByRole('textbox', { name: 'Reason' }), {
      target: { value: '  holds customer email  ' },
    });
    fireEvent.click(attach);

    await waitFor(() =>
      expect(addLocalTag).toHaveBeenCalledWith({
        targetFqn: EMAIL,
        tagFqn: 'PII.NonSensitive',
        reason: 'holds customer email',
      })
    );
  });

  it('takes off a tag set in ARAK, with a reason, and says why a refusal happened', async () => {
    fetchLocalTags.mockResolvedValue({
      canEdit: true,
      tags: [
        {
          targetFqn: EMAIL,
          assetFqn: TABLE,
          tagFqn: 'PII.Sensitive',
          reason: 'email',
          addedBy: 'steward@example.com',
          addedAt: '2026-09-27T09:00:00Z',
        },
        {
          targetFqn: `${TABLE}.id`,
          assetFqn: TABLE,
          tagFqn: 'PII.NonSensitive',
          reason: 'id',
          addedBy: 'steward@example.com',
          addedAt: '2026-09-27T09:00:00Z',
        },
      ],
    });
    removeLocalTag.mockRejectedValue({ message: 'You do not govern it, so you cannot tag it' });
    renderControl(['PII.Sensitive']);

    fireEvent.click(await screen.findByRole('button', { name: 'Edit tags' }));
    // Only this column's: the other one belongs to its own row.
    expect(screen.queryByRole('button', { name: 'Remove PII.NonSensitive' })).toBeNull();
    const removeIt = screen.getByRole('button', { name: 'Remove PII.Sensitive' });
    expect(removeIt).toBeDisabled();

    fireEvent.change(screen.getByRole('textbox', { name: 'Reason' }), {
      target: { value: 'not personal after all' },
    });
    fireEvent.click(removeIt);

    await waitFor(() =>
      expect(removeLocalTag).toHaveBeenCalledWith({
        targetFqn: EMAIL,
        tagFqn: 'PII.Sensitive',
        reason: 'not personal after all',
      })
    );
    expect(await screen.findByRole('alert')).toHaveTextContent('You do not govern it');
  });
});
