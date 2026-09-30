import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { fireEvent, render, screen, within } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import ScopePreview from './ScopePreview';
import type { Policy } from '../../generated/entity/policy/policy';

const previewPolicyScope = jest.fn();

jest.mock('../../api/policies', () => ({
  previewPolicyScope: (...args: unknown[]) => previewPolicyScope(...args),
}));

jest.mock('../../api/client', () => ({
  apiErrorMessage: (_error: unknown, fallback: string) => fallback,
}));

const TAGGED: Policy = {
  name: 'mask-pii',
  policyType: 'SUBSCRIPTION',
  scopeLevel: 'ORG',
  selector: { condition: { facet: 'tags', operator: 'contains', value: 'PII' } },
  effect: 'ALLOW',
  environment: 'prod',
} as Policy;

function renderPreview(draft: Policy) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={client}>
      <MemoryRouter>
        <ScopePreview draft={draft} />
      </MemoryRouter>
    </QueryClientProvider>
  );
}

beforeEach(() => jest.clearAllMocks());

/*
 * The panel under step 3. What it must never do is disagree with the save: it
 * shows the server's answer, from the same matcher, and says so when the list
 * is cut short rather than letting a partial list read as the whole.
 */
describe('scope preview', () => {
  test('with no condition yet it asks for one, and asks the server nothing', () => {
    renderPreview({ ...TAGGED, selector: {} });

    expect(screen.getByText(/A policy with no condition covers nothing/)).toBeInTheDocument();
    expect(previewPolicyScope).not.toHaveBeenCalled();
  });

  test('groups the covered tables by where they sit, with the counts', async () => {
    previewPolicyScope.mockResolvedValue({
      scanned: 10,
      matched: 3,
      tables: [
        { fqn: 'svc.db.sales.customer', columns: [] },
        { fqn: 'svc.db.sales.orders', columns: [] },
        { fqn: 'svc.db.hr.staff', columns: [] },
      ],
      truncated: false,
    });
    renderPreview(TAGGED);

    const sales = (await screen.findByText('svc.db.sales')).closest('li')!;
    expect(within(sales).getByRole('link', { name: 'customer' })).toHaveAttribute(
      'href',
      `/catalog/${encodeURIComponent('svc.db.sales.customer')}`
    );
    expect(within(sales).getByText('2 tables')).toBeInTheDocument();
    expect(screen.getByText('svc.db.hr')).toBeInTheDocument();
    expect(screen.getByText('30%')).toBeInTheDocument();
    expect(screen.getByText('Across every catalogued table.', { exact: false })).toBeInTheDocument();
  });

  test('a data policy shows the columns its rules pick', async () => {
    previewPolicyScope.mockResolvedValue({
      scanned: 2,
      matched: 2,
      tables: [
        { fqn: 'svc.db.sales.customer', columns: ['email', 'phone'] },
        { fqn: 'svc.db.sales.orders', columns: [] },
      ],
      truncated: false,
    });
    renderPreview({ ...TAGGED, policyType: 'DATA' } as Policy);

    expect(await screen.findByText('email')).toBeInTheDocument();
    expect(screen.getByText('phone')).toBeInTheDocument();
    expect(screen.getByText(/no column rule picks a column here/)).toBeInTheDocument();
    expect(screen.getByText('Columns picked')).toBeInTheDocument();
  });

  test('says when the list is cut short, and still gives the full count', async () => {
    previewPolicyScope.mockResolvedValue({
      scanned: 900,
      matched: 450,
      tables: [{ fqn: 'svc.db.sales.customer', columns: [] }],
      truncated: true,
    });
    renderPreview(TAGGED);

    expect(await screen.findByText(/Showing the first 1 of 450/)).toBeInTheDocument();
    expect(screen.getByText('450')).toBeInTheDocument();
  });

  test('nothing matching says the policy will pick tables up later', async () => {
    previewPolicyScope.mockResolvedValue({ scanned: 4, matched: 0, tables: [], truncated: false });
    renderPreview(TAGGED);

    expect(await screen.findByText(/No table in scope matches/)).toBeInTheDocument();
  });

  test('a long list can be filtered by name', async () => {
    previewPolicyScope.mockResolvedValue({
      scanned: 30,
      matched: 25,
      tables: Array.from({ length: 25 }, (_, index) => ({
        fqn: `svc.db.s${index % 9}.table_${index}`,
        columns: [],
      })),
      truncated: false,
    });
    renderPreview(TAGGED);

    fireEvent.change(await screen.findByLabelText('Filter covered tables'), {
      target: { value: 'table_17' },
    });
    expect(screen.getByRole('link', { name: 'table_17' })).toBeInTheDocument();
    expect(screen.queryByRole('link', { name: 'table_1' })).not.toBeInTheDocument();
  });

  test('a scoped draft says where it looks', async () => {
    previewPolicyScope.mockResolvedValue({ scanned: 1, matched: 0, tables: [], truncated: false });
    renderPreview({ ...TAGGED, scopeLevel: 'SCHEMA', scopeFqn: 'svc.db.sales' } as Policy);

    expect(screen.getByText(/Inside svc.db.sales only/)).toBeInTheDocument();
  });
});
