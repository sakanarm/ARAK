import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import SensitiveDataSection, { describeCoverage, labelOptions } from './SensitiveDataSection';
import type { GovernanceValue, Vocabulary } from '../../api/governance';
import type { SensitiveCurrent, SensitiveRule } from '../../api/sensitiveData';

const previewSensitiveData = jest.fn();
const fetchSensitiveHistory = jest.fn();
const updateSensitiveData = jest.fn();
const fetchVocabulary = jest.fn();
let current: SensitiveCurrent | undefined;

jest.mock('../../api/sensitiveData', () => ({
  ...jest.requireActual('../../api/sensitiveData'),
  useSensitiveData: () => ({ data: current, isLoading: false, error: null }),
  previewSensitiveData: (...args: unknown[]) => previewSensitiveData(...args),
  fetchSensitiveHistory: () => fetchSensitiveHistory(),
  updateSensitiveData: (...args: unknown[]) => updateSensitiveData(...args),
}));

jest.mock('../../api/governance', () => ({
  fetchVocabulary: () => fetchVocabulary(),
}));

jest.mock('../../api/client', () => ({
  apiErrorMessage: (error: { message?: string }, fallback: string) => error?.message ?? fallback,
}));

function value(fqn: string, children: GovernanceValue[] = [], extra: Partial<GovernanceValue> = {}): GovernanceValue {
  return {
    fqn,
    name: fqn.split('.').pop()!,
    parentFqn: fqn.includes('.') ? fqn.slice(0, fqn.lastIndexOf('.')) : null,
    displayName: null,
    description: null,
    depth: fqn.split('.').length - 1,
    provenance: 'openmetadata',
    disabled: false,
    mutuallyExclusive: false,
    assets: 0,
    directAssets: 0,
    policies: 0,
    children,
    ...extra,
  };
}

const VOCABULARY: Vocabulary = {
  classifications: [
    value('PII', [value('PII.Sensitive', [value('PII.Sensitive.Health')]), value('PII.NonSensitive')]),
    value('Retired', [value('Retired.Old')], { disabled: true }),
  ],
  glossaries: [value('Finance', [value('Finance.Salary')])],
  domains: [],
  dataProducts: [],
  customProperties: [],
};

const BUILT_IN_WARN: SensitiveRule = {
  builtIn: true,
  include: [],
  exclude: [],
  mode: 'WARN',
  updatedBy: 'system',
  updatedAt: '2026-09-28T03:00:00Z',
};

function renderSection(mayReview = true) {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  });
  return render(
    <QueryClientProvider client={client}>
      <SensitiveDataSection mayReview={mayReview} />
    </QueryClientProvider>
  );
}

beforeEach(() => {
  jest.clearAllMocks();
  current = { rule: BUILT_IN_WARN, canEdit: true };
  fetchVocabulary.mockResolvedValue(VOCABULARY);
  fetchSensitiveHistory.mockResolvedValue([]);
});

describe('SensitiveDataSection', () => {
  it('reads the rule: its mode, the built-in names and what it lists', () => {
    current = {
      rule: {
        ...BUILT_IN_WARN,
        mode: 'ENFORCE',
        include: [{ kind: 'CLASSIFICATION', fqn: 'Finance' }],
        exclude: [{ kind: 'TAG', fqn: 'PII.Public' }],
        updatedBy: 'author_a',
      },
      canEdit: false,
    };
    renderSection();

    const rule = screen.getByRole('article', { name: 'The sensitive data rule' });
    expect(within(rule).getByText('Enforce')).toBeInTheDocument();
    expect(within(rule).getByText(/the refusal names the purpose/)).toBeInTheDocument();
    expect(within(rule).getByText(/the built-in names/)).toBeInTheDocument();
    expect(within(rule).getByText('Finance · classification')).toBeInTheDocument();
    expect(within(rule).getByText('PII.Public · tag')).toBeInTheDocument();
    expect(within(rule).getByText(/Changed by author_a/)).toBeInTheDocument();
    expect(within(rule).queryByRole('button', { name: 'Edit' })).not.toBeInTheDocument();
  });

  it('says when nothing counts, and hides measuring and history from somebody who does not govern', () => {
    current = { rule: { ...BUILT_IN_WARN, builtIn: false }, canEdit: false };
    renderSection(false);

    expect(screen.getByText(/Nothing counts as sensitive, so nothing is checked/)).toBeInTheDocument();
    expect(screen.getByText(/As installed/)).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Measure coverage' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'History' })).not.toBeInTheDocument();
  });

  it('measures the rule in force', async () => {
    previewSensitiveData.mockResolvedValue({
      tables: 2,
      columns: 3,
      catalogTables: 10,
      examples: [
        { fqn: 'pg.sales.public.customer', columns: 2 },
        { fqn: 'pg.sales.public.notes', columns: 0 },
      ],
      labels: [{ label: 'PII.Sensitive', tables: 2, columns: 3 }],
    });
    renderSection();

    fireEvent.click(screen.getByRole('button', { name: 'Measure coverage' }));

    const coverage = await screen.findByRole('region', { name: 'Coverage' });
    expect(await within(coverage).findByText('Covers 2 of 10 tables and views, 3 columns in them')).toBeInTheDocument();
    expect(within(coverage).getByText('pg.sales.public.customer')).toBeInTheDocument();
    expect(within(coverage).getByText('the table is labelled')).toBeInTheDocument();
    expect(within(coverage).getByText('2 tables, 3 columns')).toBeInTheDocument();
    expect(previewSensitiveData).toHaveBeenCalledWith(null);
  });

  it('reads the history in words', async () => {
    fetchSensitiveHistory.mockResolvedValue([
      {
        id: 2,
        at: '2026-09-29T03:00:00Z',
        actor: 'author_a',
        reason: 'Finance tables hold salaries',
        before: { builtIn: true, include: [], exclude: [], mode: 'WARN' },
        after: {
          builtIn: true,
          include: [{ kind: 'CLASSIFICATION', fqn: 'Finance' }],
          exclude: [],
          mode: 'ENFORCE',
        },
      },
    ]);
    renderSection();

    fireEvent.click(screen.getByRole('button', { name: 'History' }));

    const history = await screen.findByRole('region', { name: 'History' });
    expect(
      await within(history).findByText(/enforce instead of warn; Finance \(classification\) counted/)
    ).toBeInTheDocument();
    expect(within(history).getByText('“Finance tables hold salaries”')).toBeInTheDocument();
  });

  it('changes the rule with a reason', async () => {
    updateSensitiveData.mockResolvedValue({ rule: BUILT_IN_WARN, canEdit: true });
    renderSection();

    fireEvent.click(screen.getByRole('button', { name: 'Edit' }));
    const form = screen.getByRole('form', { name: 'Edit what counts as sensitive data' });
    const save = within(form).getByRole('button', { name: 'Save rule' });
    expect(within(form).getByText('Nothing has changed yet.')).toBeInTheDocument();
    expect(save).toBeDisabled();

    const also = within(form).getByRole('group', { name: 'Also counts' });
    fireEvent.click(within(also).getByRole('button', { name: /Also counts: kind/ }));
    fireEvent.click(await screen.findByRole('option', { name: /^Tag/ }));
    fireEvent.change(within(also).getByRole('combobox', { name: 'Also counts: name' }), {
      target: { value: 'PII.Sensitive' },
    });
    fireEvent.click(within(also).getByRole('button', { name: 'Add' }));
    expect(within(also).getByText('PII.Sensitive · tag')).toBeInTheDocument();

    fireEvent.click(within(form).getByRole('radio', { name: /^Enforce/ }));
    expect(within(form).getByText(/Measure what it covers first/)).toBeInTheDocument();
    expect(within(form).getByText(/Say why/)).toBeInTheDocument();

    fireEvent.change(within(form).getByRole('textbox', { name: 'Why' }), {
      target: { value: 'PII is ready to be enforced' },
    });
    expect(save).toBeEnabled();
    fireEvent.click(save);

    await waitFor(() =>
      expect(updateSensitiveData).toHaveBeenCalledWith({
        builtIn: true,
        include: [{ kind: 'TAG', fqn: 'PII.Sensitive' }],
        exclude: [],
        mode: 'ENFORCE',
        reason: 'PII is ready to be enforced',
      })
    );
    await waitFor(() => expect(screen.queryByRole('form')).not.toBeInTheDocument());
  });

  it('keeps a label out of both lists, and measures the draft', async () => {
    previewSensitiveData.mockResolvedValue({
      tables: 0,
      columns: 0,
      catalogTables: 10,
      examples: [],
      labels: [],
    });
    current = {
      rule: { ...BUILT_IN_WARN, include: [{ kind: 'CLASSIFICATION', fqn: 'PII' }] },
      canEdit: true,
    };
    renderSection();

    fireEvent.click(screen.getByRole('button', { name: 'Edit' }));
    const form = screen.getByRole('form', { name: 'Edit what counts as sensitive data' });
    const never = within(form).getByRole('group', { name: 'Never counts' });
    fireEvent.change(within(never).getByRole('combobox', { name: 'Never counts: name' }), {
      target: { value: 'pii' },
    });
    fireEvent.click(within(never).getByRole('button', { name: 'Add' }));
    fireEvent.change(within(form).getByRole('textbox', { name: 'Why' }), {
      target: { value: 'Trying' },
    });

    expect(within(form).getByText('PII is in both lists; keep it in one.')).toBeInTheDocument();
    expect(within(form).getByRole('button', { name: 'Save rule' })).toBeDisabled();

    fireEvent.click(within(form).getByRole('button', { name: 'Remove PII from also counts' }));
    fireEvent.click(within(form).getByRole('checkbox', { name: /Count the built-in names/ }));
    fireEvent.click(within(form).getByRole('button', { name: 'Measure what it covers' }));

    expect(await within(form).findByText('Covers none of the 10 tables and views')).toBeInTheDocument();
    expect(previewSensitiveData).toHaveBeenCalledWith({
      builtIn: false,
      include: [],
      exclude: [{ kind: 'CLASSIFICATION', fqn: 'pii' }],
    });
    expect(within(form).getByRole('button', { name: 'Save rule' })).toBeEnabled();
  });
});

describe('labelOptions', () => {
  it('offers classifications and glossaries at the top, tags and terms beneath, none disabled', () => {
    expect(labelOptions(VOCABULARY, 'CLASSIFICATION')).toEqual(['PII']);
    expect(labelOptions(VOCABULARY, 'TAG')).toEqual([
      'PII.Sensitive',
      'PII.Sensitive.Health',
      'PII.NonSensitive',
    ]);
    expect(labelOptions(VOCABULARY, 'GLOSSARY')).toEqual(['Finance']);
    expect(labelOptions(VOCABULARY, 'TERM')).toEqual(['Finance.Salary']);
    expect(labelOptions(undefined, 'TAG')).toEqual([]);
  });
});

describe('describeCoverage', () => {
  it('says how far a rule reaches', () => {
    const coverage = { tables: 1, columns: 1, catalogTables: 1200, examples: [], labels: [] };
    expect(describeCoverage(coverage)).toBe('Covers 1 of 1,200 tables and views, 1 column in them');
    expect(describeCoverage({ ...coverage, tables: 0 })).toBe('Covers none of the 1,200 tables and views');
  });
});
