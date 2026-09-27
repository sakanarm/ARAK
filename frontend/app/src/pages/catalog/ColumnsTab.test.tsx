import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { ColumnsTab } from './ColumnsTab';
import type { ColumnDetail, FacetRow } from '../../api/client';

const fetchColumnDescriptions = jest.fn();
const saveColumnDescriptions = jest.fn();
const assistDescribeColumns = jest.fn();
let assistReady = false;

jest.mock('../../api/client', () => ({
  apiErrorMessage: (error: unknown, fallback: string) =>
    (error as { message?: string })?.message ?? fallback,
}));
jest.mock('../../api/columnDescriptions', () => ({
  fetchColumnDescriptions: (...args: unknown[]) => fetchColumnDescriptions(...args),
  saveColumnDescriptions: (...args: unknown[]) => saveColumnDescriptions(...args),
}));
jest.mock('../../api/llm', () => ({
  DESCRIBE_BATCH: 40,
  assistDescribeColumns: (...args: unknown[]) => assistDescribeColumns(...args),
}));
jest.mock('../../assist/useAssist', () => ({ useAssistReady: () => assistReady }));
// Tagging has its own tests; here it only has to show where its button sits.
jest.mock('./LocalTags', () => ({
  LocalTagControl: () => <button type="button">Edit tags</button>,
}));

const TABLE = 'demo-pg.salesdb.sales.customer';

function column(name: string, overrides: Partial<ColumnDetail> = {}): ColumnDetail {
  return {
    id: name,
    fqn: `${TABLE}.${name}`,
    name,
    ordinal: 1,
    dataType: 'VARCHAR',
    dataLength: null,
    nullable: true,
    description: null,
    descriptionSource: null,
    facets: [],
    ...overrides,
  };
}

const TAG: FacetRow = {
  facetType: 'tags',
  facetFqn: 'PII.Sensitive',
  property: null,
  depth: 0,
  direct: true,
  inheritedFrom: null,
  provenance: 'openmetadata',
  omState: 'Confirmed',
  omLabelType: 'Manual',
};

const COLUMNS = [
  column('id', { dataType: 'BIGINT' }),
  column('email', { facets: [TAG] }),
  column('branch_code', {
    description: 'The branch that holds the account',
    descriptionSource: 'arak',
  }),
  column('country', { description: 'ISO country code', descriptionSource: 'openmetadata' }),
];

const WRITTEN = {
  columnFqn: `${TABLE}.branch_code`,
  assetFqn: TABLE,
  description: 'The branch that holds the account',
  assisted: true,
  writtenBy: 'owner_a',
  writtenAt: '2026-09-01T00:00:00Z',
};

function renderTab(columns: ColumnDetail[] = COLUMNS) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  render(
    <QueryClientProvider client={client}>
      <ColumnsTab assetFqn={TABLE} columns={columns} />
    </QueryClientProvider>
  );
}

function row(name: string): HTMLElement {
  const cell = screen.getAllByText(name, { selector: 'span' })[0];
  return cell.closest('tr') as HTMLElement;
}

beforeEach(() => {
  jest.resetAllMocks();
  assistReady = false;
  fetchColumnDescriptions.mockResolvedValue({ canEdit: false, descriptions: [WRITTEN] });
});

describe('ColumnsTab, read', () => {
  it('shows every description, says which were written here, and offers nothing to change', async () => {
    renderTab();

    expect(await screen.findByText('The branch that holds the account')).toBeInTheDocument();
    expect(screen.getByText('ISO country code')).toBeInTheDocument();
    await waitFor(() =>
      expect(screen.getByText('ARAK')).toHaveAttribute(
        'title',
        expect.stringContaining('Written in ARAK by owner_a')
      )
    );
    expect(screen.getByText('ARAK').getAttribute('title')).toContain('from a NokRak draft');
    expect(screen.getByText('4 columns · 1 carrying governance of their own · 2 described')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Describe columns' })).toBeNull();
  });

  it('puts a dash in an empty governance cell for somebody who cannot tag it', async () => {
    renderTab();
    await screen.findByText('ISO country code');
    expect(within(row('id')).getByText('—')).toBeInTheDocument();
  });

  it('shows a steward the Edit tags button alone, with no dash in front of it', async () => {
    fetchColumnDescriptions.mockResolvedValue({ canEdit: true, descriptions: [WRITTEN] });
    renderTab();
    await screen.findByRole('button', { name: 'Describe columns' });
    // "—Edit tags" read as one odd button; the button alone is the answer.
    expect(within(row('id')).queryByText('—')).toBeNull();
    expect(within(row('id')).getByRole('button', { name: 'Edit tags' })).toBeInTheDocument();
  });

  it('finds a column by its name or by what its description says', async () => {
    const many = Array.from({ length: 10 }, (_, i) => column(`col_${i}`)).concat(COLUMNS);
    renderTab(many);
    await screen.findByText('ISO country code');

    fireEvent.change(screen.getByRole('searchbox', { name: 'Find a column' }), {
      target: { value: 'country code' },
    });
    expect(screen.getAllByRole('row')).toHaveLength(2);
    expect(screen.getByText('country')).toBeInTheDocument();
  });
});

describe('ColumnsTab, write', () => {
  beforeEach(() => {
    fetchColumnDescriptions.mockResolvedValue({ canEdit: true, descriptions: [WRITTEN] });
  });

  it('saves only what changed, under the column it describes', async () => {
    saveColumnDescriptions.mockResolvedValue({ set: 1, cleared: 1, unchanged: 0, descriptions: [] });
    renderTab();
    fireEvent.click(await screen.findByRole('button', { name: 'Describe columns' }));

    // OpenMetadata's words are the placeholder, not the value: an empty field keeps them.
    expect(screen.getByRole('textbox', { name: 'Description of country' })).toHaveAttribute(
      'placeholder',
      'OpenMetadata says: ISO country code'
    );
    expect(screen.getByRole('textbox', { name: 'Description of branch_code' })).toHaveValue(
      'The branch that holds the account'
    );
    expect(screen.getByRole('button', { name: 'Nothing to save' })).toBeDisabled();

    fireEvent.change(screen.getByRole('textbox', { name: 'Description of email' }), {
      target: { value: "  The customer's work email " },
    });
    fireEvent.change(screen.getByRole('textbox', { name: 'Description of branch_code' }), {
      target: { value: '' },
    });
    fireEvent.click(screen.getByRole('button', { name: 'Save 2 changes' }));

    await waitFor(() => expect(saveColumnDescriptions).toHaveBeenCalled());
    expect(saveColumnDescriptions.mock.calls[0][0]).toEqual({
      assetFqn: TABLE,
      entries: [
        { columnFqn: `${TABLE}.email`, description: "The customer's work email", assisted: false },
        { columnFqn: `${TABLE}.branch_code`, description: '', assisted: false },
      ],
    });
    expect(await screen.findByText('Saved: 1 written, 1 taken away.')).toBeInTheDocument();
    expect(screen.queryByRole('textbox', { name: 'Description of email' })).toBeNull();
  });

  it('says why a save was refused, and keeps what was typed', async () => {
    saveColumnDescriptions.mockRejectedValue(new Error('x is not a current column of the table'));
    renderTab();
    fireEvent.click(await screen.findByRole('button', { name: 'Describe columns' }));
    fireEvent.change(screen.getByRole('textbox', { name: 'Description of id' }), {
      target: { value: 'Key' },
    });
    fireEvent.click(screen.getByRole('button', { name: 'Save 1 change' }));

    expect(await screen.findByRole('alert')).toHaveTextContent('not a current column');
    expect(screen.getByRole('textbox', { name: 'Description of id' })).toHaveValue('Key');
  });
});

describe('ColumnsTab, NokRak', () => {
  beforeEach(() => {
    assistReady = true;
    fetchColumnDescriptions.mockResolvedValue({ canEdit: true, descriptions: [WRITTEN] });
  });

  it('drafts only the columns nobody has described, and saves nothing by itself', async () => {
    assistDescribeColumns.mockResolvedValue({
      drafts: [{ name: 'EMAIL', description: "The customer's email address" }],
      model: 'm',
      personal: false,
    });
    saveColumnDescriptions.mockResolvedValue({ set: 1, cleared: 0, unchanged: 0, descriptions: [] });
    renderTab();
    fireEvent.click(await screen.findByRole('button', { name: 'Describe columns' }));
    fireEvent.change(screen.getByRole('combobox', { name: 'Write the drafts in' }), {
      target: { value: 'Thai' },
    });
    fireEvent.click(screen.getByRole('button', { name: 'Draft 2 empty with NokRak' }));

    await waitFor(() =>
      expect(screen.getByRole('textbox', { name: 'Description of email' })).toHaveValue(
        "The customer's email address"
      )
    );
    expect(assistDescribeColumns).toHaveBeenCalledWith({
      assetFqn: TABLE,
      columns: ['id', 'email'],
      language: 'Thai',
    });
    // It could not tell what `id` holds, and an empty field says so.
    expect(screen.getByRole('textbox', { name: 'Description of id' })).toHaveValue('');
    expect(within(row('email')).getByText(/NokRak draft/)).toBeInTheDocument();
    expect(await screen.findByText(/NokRak drafted 1 of 2/)).toBeInTheDocument();
    expect(saveColumnDescriptions).not.toHaveBeenCalled();

    fireEvent.click(screen.getByRole('button', { name: 'Save 1 change' }));
    await waitFor(() => expect(saveColumnDescriptions).toHaveBeenCalled());
    expect(saveColumnDescriptions.mock.calls[0][0].entries).toEqual([
      { columnFqn: `${TABLE}.email`, description: "The customer's email address", assisted: true },
    ]);
  });

  it('asks about a wide table forty columns at a time', async () => {
    const wide = Array.from({ length: 45 }, (_, i) => column(`c${i}`));
    assistDescribeColumns.mockImplementation(({ columns }: { columns: string[] }) =>
      Promise.resolve({
        drafts: columns.map((name) => ({ name, description: `About ${name}` })),
        model: 'm',
        personal: false,
      })
    );
    renderTab(wide);
    fireEvent.click(await screen.findByRole('button', { name: 'Describe columns' }));
    fireEvent.click(screen.getByRole('button', { name: 'Draft 45 empty with NokRak' }));

    expect(await screen.findByText(/NokRak drafted 45 of 45/)).toBeInTheDocument();
    expect(assistDescribeColumns).toHaveBeenCalledTimes(2);
    expect(assistDescribeColumns.mock.calls[0][0].columns).toHaveLength(40);
    expect(assistDescribeColumns.mock.calls[1][0].columns).toEqual(['c40', 'c41', 'c42', 'c43', 'c44']);
    expect(screen.getByRole('textbox', { name: 'Description of c44' })).toHaveValue('About c44');
  });

  it('is not offered to somebody the assistant is not set up for', async () => {
    assistReady = false;
    renderTab();
    fireEvent.click(await screen.findByRole('button', { name: 'Describe columns' }));
    expect(screen.queryByRole('button', { name: /with NokRak/ })).toBeNull();
  });
});
