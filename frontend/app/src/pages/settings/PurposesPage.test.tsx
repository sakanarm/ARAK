import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import PurposesPage, { changedFields, describeUsage, keyFor, purposeProblem } from './PurposesPage';
import type { NewPurpose, Purpose, PurposeListing, Uses } from '../../api/purposes';

const fetchPurposeUsage = jest.fn();
const fetchPurposeHistory = jest.fn();
const createPurpose = jest.fn();
const updatePurpose = jest.fn();
const retirePurpose = jest.fn();
const reinstatePurpose = jest.fn();
let listing: PurposeListing | undefined;
let roles: string[] = ['PLATFORM_ADMIN'];

jest.mock('../../api/purposes', () => ({
  ...jest.requireActual('../../api/purposes'),
  usePurposes: () => ({ data: listing, isLoading: false, error: null }),
  fetchPurposeUsage: () => fetchPurposeUsage(),
  fetchPurposeHistory: (...args: unknown[]) => fetchPurposeHistory(...args),
  createPurpose: (...args: unknown[]) => createPurpose(...args),
  updatePurpose: (...args: unknown[]) => updatePurpose(...args),
  retirePurpose: (...args: unknown[]) => retirePurpose(...args),
  reinstatePurpose: (...args: unknown[]) => reinstatePurpose(...args),
}));

jest.mock('../../api/client', () => ({
  apiErrorMessage: (error: { message?: string }, fallback: string) => error?.message ?? fallback,
}));

jest.mock('../../auth/authStore', () => ({
  useAuthStore: (selector: (state: unknown) => unknown) =>
    selector({
      user: { username: 'admin' },
      hasRole: (...wanted: string[]) => wanted.some((role) => roles.includes(role)),
    }),
}));

function purpose(key: string, name: string, extra: Partial<Purpose> = {}): Purpose {
  return {
    key,
    name,
    description: null,
    legalBasis: null,
    sensitiveAllowed: false,
    owner: null,
    maxDays: null,
    status: 'ACTIVE',
    createdBy: 'admin',
    createdAt: '2026-09-28T03:00:00Z',
    updatedBy: 'admin',
    updatedAt: '2026-09-28T03:00:00Z',
    ...extra,
  };
}

const FRAUD = purpose('fraud-analysis', 'Fraud analysis', {
  description: 'Investigating suspected fraud',
  legalBasis: 'LEGITIMATE_INTEREST',
  sensitiveAllowed: true,
  owner: 'Risk team',
  maxDays: 30,
});
const REPORTING = purpose('reporting', 'Reporting');
const SUPPORT = purpose('support', 'Support', { status: 'RETIRED', legalBasis: 'CONTRACT' });

const USES: Uses = {
  listed: {
    'fraud-analysis': { value: 'fraud-analysis', policies: 2, templates: 1, openRequests: 3, recentQueries: 14 },
    reporting: { value: 'reporting', policies: 0, templates: 0, openRequests: 0, recentQueries: 0 },
  },
  unlisted: [{ value: 'Month-end close', policies: 1, templates: 0, openRequests: 0, recentQueries: 0 }],
};

function renderPage() {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  });
  return render(
    <QueryClientProvider client={client}>
      <PurposesPage />
    </QueryClientProvider>
  );
}

beforeEach(() => {
  jest.clearAllMocks();
  roles = ['PLATFORM_ADMIN'];
  listing = { purposes: [REPORTING, SUPPORT, FRAUD], canEdit: true };
  fetchPurposeUsage.mockResolvedValue(USES);
});

describe('PurposesPage', () => {
  it('lists the purposes in use by name, what each rests on, and where it is named', async () => {
    renderPage();

    const inUse = screen.getByRole('region', { name: 'In use' });
    const cards = within(inUse).getAllByRole('article');
    expect(cards.map((card) => card.getAttribute('aria-label'))).toEqual([
      'Purpose Fraud analysis',
      'Purpose Reporting',
    ]);
    const fraud = cards[0];
    expect(fraud).toHaveTextContent('fraud-analysis');
    expect(fraud).toHaveTextContent('Legitimate interest');
    expect(fraud).toHaveTextContent('Sensitive data allowed');
    expect(fraud).toHaveTextContent('At most 30 days');
    expect(fraud).toHaveTextContent('Answered for by Risk team');
    expect(await within(fraud).findByText(/2 policies · 1 template · 3 open requests · 14 queries in 90 days/)).toBeInTheDocument();
    expect(cards[1]).toHaveTextContent('No legal basis said');
    expect(cards[1]).toHaveTextContent('Nobody named to answer for it · Not named anywhere yet');

    const retired = screen.getByRole('region', { name: 'Retired' });
    expect(within(retired).getByRole('article', { name: 'Purpose Support' })).toHaveTextContent('Retired');
    expect(within(retired).getByRole('button', { name: 'Reinstate' })).toBeInTheDocument();

    const unlisted = await screen.findByRole('region', { name: 'Named but not listed' });
    expect(within(unlisted).getByRole('article', { name: 'Unlisted Month-end close' })).toHaveTextContent('1 policy');
  });

  it('lets an auditor read the history and the unlisted values but change nothing', async () => {
    roles = ['AUDITOR'];
    listing = { ...listing!, canEdit: false };
    renderPage();

    expect(screen.queryByRole('button', { name: 'New purpose' })).toBeNull();
    const fraud = screen.getByRole('article', { name: 'Purpose Fraud analysis' });
    expect(within(fraud).queryByRole('button', { name: 'Edit' })).toBeNull();
    expect(within(fraud).queryByRole('button', { name: 'Retire' })).toBeNull();
    expect(within(fraud).getByRole('button', { name: 'History' })).toBeInTheDocument();
    const unlisted = await screen.findByRole('region', { name: 'Named but not listed' });
    expect(within(unlisted).queryByRole('button', { name: 'List it' })).toBeNull();
  });

  it('shows everybody else only the register, without asking where it is used', () => {
    roles = [];
    listing = { ...listing!, canEdit: false };
    renderPage();

    expect(screen.getByRole('article', { name: 'Purpose Fraud analysis' })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'History' })).toBeNull();
    expect(screen.queryByRole('region', { name: 'Named but not listed' })).toBeNull();
    expect(fetchPurposeUsage).not.toHaveBeenCalled();
  });

  it('lists a new purpose once it has a key and a name', async () => {
    createPurpose.mockResolvedValue(purpose('audit', 'Audit'));
    renderPage();
    fireEvent.click(screen.getByRole('button', { name: 'New purpose' }));
    const form = screen.getByRole('form', { name: 'New purpose' });
    const list = within(form).getByRole('button', { name: 'List purpose' });
    expect(list).toBeDisabled();
    expect(form).toHaveTextContent('Give it a key: it is what policies and requests store.');

    fireEvent.change(within(form).getByLabelText('Purpose key'), { target: { value: 'Internal Audit' } });
    expect(form).toHaveTextContent('A key is up to 63 lower-case letters');
    fireEvent.change(within(form).getByLabelText('Purpose key'), { target: { value: 'Internal-Audit' } });
    expect(form).toHaveTextContent('Name it: the name is what people pick from.');
    fireEvent.change(within(form).getByLabelText('Purpose name'), { target: { value: ' Internal audit ' } });
    fireEvent.change(within(form).getByLabelText('Longest access'), { target: { value: '9x0' } });
    expect(within(form).getByLabelText('Longest access')).toHaveValue('90');
    fireEvent.change(within(form).getByLabelText('Purpose owner'), { target: { value: 'Audit team' } });
    fireEvent.click(within(form).getByRole('checkbox'));
    fireEvent.click(within(form).getByRole('button', { name: /Legal basis/ }));
    fireEvent.click(await screen.findByRole('option', { name: /^Legal obligation/ }));
    expect(form).toHaveTextContent('ม.24(6) — a law requires it');

    expect(list).toBeEnabled();
    fireEvent.click(list);
    await waitFor(() =>
      expect(createPurpose).toHaveBeenCalledWith({
        key: 'internal-audit',
        name: 'Internal audit',
        description: null,
        legalBasis: 'LEGAL_OBLIGATION',
        sensitiveAllowed: true,
        owner: 'Audit team',
        maxDays: 90,
      })
    );
    await waitFor(() => expect(screen.queryByRole('form', { name: 'New purpose' })).toBeNull());
  });

  it('edits a purpose without touching its key', async () => {
    updatePurpose.mockResolvedValue(FRAUD);
    renderPage();
    const fraud = screen.getByRole('article', { name: 'Purpose Fraud analysis' });
    fireEvent.click(within(fraud).getByRole('button', { name: 'Edit' }));

    const form = screen.getByRole('form', { name: 'Edit Fraud analysis' });
    expect(within(form).queryByLabelText('Purpose key')).toBeNull();
    expect(form).toHaveTextContent('Fixed, because policies and requests store it.');
    // Only one thing is edited at a time.
    expect(screen.queryByRole('button', { name: 'New purpose' })).toBeNull();
    fireEvent.change(within(form).getByLabelText('Longest access'), { target: { value: '' } });
    fireEvent.click(within(form).getByRole('button', { name: 'Save purpose' }));

    await waitFor(() =>
      expect(updatePurpose).toHaveBeenCalledWith('fraud-analysis', {
        name: 'Fraud analysis',
        description: 'Investigating suspected fraud',
        legalBasis: 'LEGITIMATE_INTEREST',
        sensitiveAllowed: true,
        owner: 'Risk team',
        maxDays: null,
      })
    );
  });

  it('retires a purpose only with a reason, and reinstates one the same way', async () => {
    retirePurpose.mockResolvedValue({ ...REPORTING, status: 'RETIRED' });
    reinstatePurpose.mockResolvedValue({ ...SUPPORT, status: 'ACTIVE' });
    renderPage();

    const reporting = screen.getByRole('article', { name: 'Purpose Reporting' });
    fireEvent.click(within(reporting).getByRole('button', { name: 'Retire' }));
    const dialog = within(reporting).getByRole('alertdialog', { name: 'Retire the purpose' });
    expect(dialog).toHaveTextContent('nothing new may ask for it and a query naming it is refused');
    const retire = within(dialog).getByRole('button', { name: 'Retire purpose' });
    expect(retire).toBeDisabled();
    fireEvent.change(within(dialog).getByLabelText('Why'), { target: { value: ' No longer reported ' } });
    fireEvent.click(retire);
    await waitFor(() => expect(retirePurpose).toHaveBeenCalledWith('reporting', 'No longer reported'));

    const support = screen.getByRole('article', { name: 'Purpose Support' });
    fireEvent.click(within(support).getByRole('button', { name: 'Reinstate' }));
    const back = within(support).getByRole('alertdialog', { name: 'Reinstate the purpose' });
    fireEvent.change(within(back).getByLabelText('Why'), { target: { value: 'Support desk reopened' } });
    fireEvent.click(within(back).getByRole('button', { name: 'Reinstate purpose' }));
    await waitFor(() => expect(reinstatePurpose).toHaveBeenCalledWith('support', 'Support desk reopened'));
  });

  it('shows who changed what, and why', async () => {
    fetchPurposeHistory.mockResolvedValue([
      {
        id: 2,
        occurredAt: '2026-09-28T04:00:00Z',
        actor: 'dpo',
        action: 'UPDATE',
        reason: null,
        before: { name: 'Fraud', maxDays: 90, owner: 'Risk team' },
        after: { name: 'Fraud analysis', maxDays: 30, owner: 'Risk team' },
      },
      {
        id: 1,
        occurredAt: '2026-09-28T03:00:00Z',
        actor: 'admin',
        action: 'RETIRE',
        reason: 'Merged into fraud analysis',
        before: null,
        after: null,
      },
    ]);
    renderPage();
    const fraud = screen.getByRole('article', { name: 'Purpose Fraud analysis' });
    fireEvent.click(within(fraud).getByRole('button', { name: 'History' }));

    const history = await within(fraud).findByRole('region', { name: 'History' });
    expect(await within(history).findByText(/changed the name, the longest access/)).toBeInTheDocument();
    expect(history).toHaveTextContent('retired it');
    expect(history).toHaveTextContent('“Merged into fraud analysis”');
    expect(fetchPurposeHistory).toHaveBeenCalledWith('fraud-analysis');
  });

  it('lists a value that is named but not in the register, keyed the way the register would', async () => {
    renderPage();
    const unlisted = await screen.findByRole('region', { name: 'Named but not listed' });
    fireEvent.click(within(unlisted).getByRole('button', { name: 'List it' }));

    const form = screen.getByRole('form', { name: 'New purpose' });
    expect(within(form).getByLabelText('Purpose key')).toHaveValue('month-end-close');
    expect(within(form).getByLabelText('Purpose name')).toHaveValue('Month-end close');
  });
});

describe('keyFor', () => {
  it('lower-cases a typed value and joins its words with dashes', () => {
    expect(keyFor(' Month-end Close ')).toBe('month-end-close');
    expect(keyFor('--Fraud / AML!!')).toBe('fraud-aml');
    expect(keyFor('v1.2_report')).toBe('v1.2_report');
    expect(keyFor('x'.repeat(80))).toHaveLength(63);
  });
});

describe('purposeProblem', () => {
  const draft = (extra: Partial<NewPurpose> = {}): NewPurpose => ({
    key: 'audit',
    name: 'Audit',
    description: null,
    legalBasis: null,
    sensitiveAllowed: false,
    owner: null,
    maxDays: null,
    ...extra,
  });

  it('says what the server would refuse first', () => {
    expect(purposeProblem(draft(), true)).toBeNull();
    expect(purposeProblem(draft({ key: '' }), true)).toMatch(/Give it a key/);
    expect(purposeProblem(draft({ key: '-audit' }), true)).toMatch(/A key is up to 63/);
    expect(purposeProblem(draft({ key: '' }), false)).toBeNull();
    expect(purposeProblem(draft({ name: ' ' }), true)).toMatch(/Name it/);
    expect(purposeProblem(draft({ name: 'x'.repeat(121) }), true)).toBe('Keep the name to 120 characters');
    expect(purposeProblem(draft({ description: 'x'.repeat(2001) }), true)).toBe(
      'Keep the description to 2,000 characters'
    );
    expect(purposeProblem(draft({ owner: 'x'.repeat(201) }), true)).toBe('Keep the owner to 200 characters');
    expect(purposeProblem(draft({ maxDays: 0 }), true)).toMatch(/between 1 and 365 days/);
    expect(purposeProblem(draft({ maxDays: 366 }), true)).toMatch(/between 1 and 365 days/);
    expect(purposeProblem(draft({ maxDays: 365 }), true)).toBeNull();
  });
});

describe('describeUsage', () => {
  it('counts where a value is named, or says it is named nowhere', () => {
    expect(describeUsage(undefined)).toBeNull();
    expect(describeUsage({ value: 'x', policies: 0, templates: 0, openRequests: 0, recentQueries: 0 })).toBe(
      'Not named anywhere yet'
    );
    expect(describeUsage({ value: 'x', policies: 1, templates: 0, openRequests: 1, recentQueries: 1200 })).toBe(
      '1 policy · 1 open request · 1,200 queries in 90 days'
    );
  });
});

describe('changedFields', () => {
  it('names the details a change touched, in the form’s order', () => {
    expect(
      changedFields({
        before: { maxDays: 90, name: 'Fraud', sensitiveAllowed: false },
        after: { maxDays: 30, name: 'Fraud', sensitiveAllowed: true },
      })
    ).toEqual(['sensitive data', 'the longest access']);
    expect(changedFields({ before: { owner: null }, after: {} })).toEqual([]);
    expect(changedFields({ before: null, after: { name: 'x' } })).toEqual([]);
  });
});
