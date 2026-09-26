import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import RequestTemplatesPage, { parseDurations, parsePurposes, problemOf } from './RequestTemplatesPage';
import type {
  RequestForm,
  RequestTemplate,
  TemplateDraft,
  TemplateListing,
  TemplateRow,
} from '../../api/requestTemplates';

const fetchTemplates = jest.fn();
const createTemplate = jest.fn();
const updateTemplate = jest.fn();
const deleteTemplate = jest.fn();
const fetchTemplateHistory = jest.fn();
const fetchAssets = jest.fn();
const fetchVocabulary = jest.fn();
let roles: string[] = ['PLATFORM_ADMIN'];

jest.mock('../../api/requestTemplates', () => {
  const actual = jest.requireActual('../../api/requestTemplates');
  return {
    ...actual,
    fetchTemplates: () => fetchTemplates(),
    createTemplate: (...args: unknown[]) => createTemplate(...args),
    updateTemplate: (...args: unknown[]) => updateTemplate(...args),
    deleteTemplate: (...args: unknown[]) => deleteTemplate(...args),
    fetchTemplateHistory: (...args: unknown[]) => fetchTemplateHistory(...args),
  };
});

jest.mock('../../api/client', () => ({
  apiErrorMessage: (error: { message?: string }, fallback: string) => error?.message ?? fallback,
  fetchAssets: (...args: unknown[]) => fetchAssets(...args),
}));

jest.mock('../../api/governance', () => ({
  fetchVocabulary: () => fetchVocabulary(),
  flatten: jest.requireActual('../../api/governance').flatten,
}));

jest.mock('../../auth/authStore', () => ({
  useAuthStore: (selector: (state: unknown) => unknown) =>
    selector({
      user: { username: 'admin' },
      hasRole: (...wanted: string[]) => wanted.some((role) => roles.includes(role)),
    }),
}));

const BUILT_IN_FORM: RequestForm = {
  purposes: [],
  purposeRequired: false,
  durations: [7, 30, 90],
  defaultDays: 30,
  maxDays: null,
  allowUntilRevoked: true,
  referenceLabel: null,
  referenceRequired: false,
  minReasonLength: 1,
  guidance: null,
};

const BUILT_IN: RequestTemplate = {
  id: null,
  name: 'Built-in',
  description: null,
  scopeFqn: null,
  matchFacets: [],
  enabled: true,
  form: BUILT_IN_FORM,
};

const PII: RequestTemplate = {
  id: 'tpl-pii',
  name: 'PII tables',
  description: 'Personal data needs a DPIA',
  scopeFqn: null,
  matchFacets: ['PII'],
  enabled: true,
  form: {
    purposes: ['Fraud investigation', 'Regulatory report'],
    purposeRequired: true,
    durations: [7, 14],
    defaultDays: 7,
    maxDays: 30,
    allowUntilRevoked: false,
    referenceLabel: 'DPIA number',
    referenceRequired: true,
    minReasonLength: 20,
    guidance: 'Name the case.' + String.fromCharCode(10) + '<b>not html</b>',
  },
};

const SALES: RequestTemplate = {
  id: 'tpl-sales',
  name: 'Sales',
  description: null,
  scopeFqn: 'demo-pg.salesdb',
  matchFacets: [],
  enabled: false,
  form: { ...BUILT_IN_FORM, referenceLabel: 'Change ticket' },
};

function row(template: RequestTemplate, overrides: Partial<TemplateRow> = {}): TemplateRow {
  return {
    template,
    createdBy: 'admin',
    createdAt: new Date(Date.now() - 3600_000).toISOString(),
    updatedBy: null,
    updatedAt: null,
    canEdit: true,
    ...overrides,
  };
}

function listing(overrides: Partial<TemplateListing> = {}): TemplateListing {
  return { templates: [], builtIn: BUILT_IN, canCreateDefault: true, ...overrides };
}

function renderPage() {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  });
  return render(
    <QueryClientProvider client={client}>
      <RequestTemplatesPage />
    </QueryClientProvider>
  );
}

beforeEach(() => {
  jest.clearAllMocks();
  roles = ['PLATFORM_ADMIN'];
  fetchAssets.mockResolvedValue({ items: [], total: 0 });
  fetchVocabulary.mockResolvedValue({
    classifications: [],
    glossaries: [],
    domains: [],
    dataProducts: [],
    customProperties: [],
  });
});

describe('RequestTemplatesPage', () => {
  it('lists templates by tag or term, then by scope, and ends with the built-in form', async () => {
    fetchTemplates.mockResolvedValue(listing({ templates: [row(SALES), row(PII)] }));
    renderPage();

    const byLabel = await screen.findByRole('region', { name: 'By tag or term' });
    const pii = within(byLabel).getByRole('article', { name: 'Template PII tables' });
    expect(pii).toHaveTextContent('Every table in the organisation that carries one of');
    expect(pii).toHaveTextContent('PII');
    expect(pii).toHaveTextContent('Asks a purpose from 2, DPIA number, a reason of 20+ characters; up to 30 days.');

    const byScope = screen.getByRole('region', { name: 'By scope' });
    const sales = within(byScope).getByRole('article', { name: 'Template Sales' });
    expect(sales).toHaveTextContent('Tables under demo-pg.salesdb');
    expect(sales).toHaveTextContent('Off');

    const last = screen.getByRole('region', { name: 'When nothing else applies' });
    const builtIn = within(last).getByRole('article', { name: 'Template Built-in' });
    expect(within(builtIn).queryByRole('button', { name: 'Edit' })).not.toBeInTheDocument();
    expect(builtIn).toHaveTextContent('Asks a reason; any length, or until revoked.');
  });

  it('previews the form with the guidance as plain text, never as HTML', async () => {
    fetchTemplates.mockResolvedValue(listing({ templates: [row(PII)] }));
    renderPage();
    const card = await screen.findByRole('article', { name: 'Template PII tables' });
    fireEvent.click(within(card).getByRole('button', { name: 'Preview' }));

    const preview = within(card).getByRole('region', { name: 'What the requester sees' });
    const guidance = within(preview).getByRole('note', { name: 'Guidance' });
    expect(guidance).toHaveTextContent('<b>not html</b>');
    expect(guidance.querySelector('b')).toBeNull();
    expect(preview).toHaveTextContent('One of: Fraud investigation, Regulatory report');
    expect(preview).toHaveTextContent('DPIA number');
    expect(preview).toHaveTextContent('at most 30 days');
    expect(preview).not.toHaveTextContent('Until revoked');
  });

  it('hides Edit and Delete where the reader may not change it, and hides New for an auditor', async () => {
    roles = ['AUDITOR'];
    fetchTemplates.mockResolvedValue(listing({ templates: [row(PII, { canEdit: false })], canCreateDefault: false }));
    renderPage();
    const card = await screen.findByRole('article', { name: 'Template PII tables' });
    expect(within(card).queryByRole('button', { name: 'Edit' })).not.toBeInTheDocument();
    expect(within(card).queryByRole('button', { name: 'Delete' })).not.toBeInTheDocument();
    expect(within(card).getByRole('button', { name: 'History' })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'New template' })).not.toBeInTheDocument();
  });

  it('shows the history and deletes after a confirmation', async () => {
    fetchTemplates.mockResolvedValue(listing({ templates: [row(PII)] }));
    fetchTemplateHistory.mockResolvedValue([
      { id: 1, occurredAt: new Date().toISOString(), actor: 'admin', action: 'CREATE', scopeFqn: null },
    ]);
    deleteTemplate.mockResolvedValue(undefined);
    renderPage();
    const card = await screen.findByRole('article', { name: 'Template PII tables' });

    fireEvent.click(within(card).getByRole('button', { name: 'History' }));
    expect(await within(card).findByText('created it')).toBeInTheDocument();

    fireEvent.click(within(card).getByRole('button', { name: 'Delete' }));
    expect(within(card).getByRole('alertdialog', { name: 'Delete the template' })).toHaveTextContent(
      'Requests already made keep what they were asked'
    );
    fireEvent.click(within(card).getByRole('button', { name: 'Delete template' }));
    await waitFor(() => expect(deleteTemplate).toHaveBeenCalledWith('tpl-pii'));
  });

  it('creates a template from what was typed, with a live preview', async () => {
    fetchTemplates.mockResolvedValue(listing());
    createTemplate.mockResolvedValue(row(PII));
    renderPage();
    fireEvent.click(await screen.findByRole('button', { name: 'New template' }));
    const form = screen.getByRole('form', { name: 'New template' });
    const create = within(form).getByRole('button', { name: 'Create template' });
    expect(create).toBeDisabled();
    expect(form).toHaveTextContent('Name the template.');

    fireEvent.change(within(form).getByLabelText('Template name'), { target: { value: ' PII tables ' } });
    fireEvent.change(within(form).getByLabelText('Add a tag or term'), { target: { value: 'PII' } });
    fireEvent.click(within(form).getByRole('button', { name: 'Add' }));
    fireEvent.change(within(form).getByLabelText('Purposes offered'), {
      target: { value: ['Fraud investigation', '', 'fraud investigation', 'Regulatory report'].join(String.fromCharCode(10)) },
    });
    fireEvent.click(within(form).getByLabelText('A purpose is required'));
    fireEvent.change(within(form).getByLabelText('Reference label'), { target: { value: 'DPIA number' } });
    fireEvent.click(within(form).getByLabelText('The reference is required'));
    fireEvent.change(within(form).getByLabelText('Shortest reason'), { target: { value: '20' } });
    fireEvent.change(within(form).getByLabelText('Suggested durations'), { target: { value: '14, 7 7' } });
    // A longest duration switches "until revoked" off: the two contradict.
    fireEvent.change(within(form).getByLabelText('Longest duration'), { target: { value: '30' } });
    expect(within(form).getByLabelText(/Access until revoked may be asked for/)).not.toBeChecked();
    fireEvent.change(within(form).getByLabelText('Default duration'), { target: { value: '7' } });
    fireEvent.change(within(form).getByLabelText('Guidance'), { target: { value: 'Name the case.' } });

    const preview = within(form).getByRole('region', { name: 'What the requester sees' });
    expect(preview).toHaveTextContent('One of: Fraud investigation, Regulatory report');
    expect(within(preview).getByRole('note', { name: 'Guidance' })).toHaveTextContent('Name the case.');

    expect(create).toBeEnabled();
    fireEvent.click(create);
    await waitFor(() => expect(createTemplate).toHaveBeenCalled());
    expect(createTemplate.mock.calls[0][0]).toEqual({
      name: 'PII tables',
      description: null,
      scopeFqn: null,
      matchFacets: ['PII'],
      enabled: true,
      form: {
        purposes: ['Fraud investigation', 'Regulatory report'],
        purposeRequired: true,
        durations: [7, 14],
        defaultDays: 7,
        maxDays: 30,
        allowUntilRevoked: false,
        referenceLabel: 'DPIA number',
        referenceRequired: true,
        minReasonLength: 20,
        guidance: 'Name the case.',
      },
    });
  });

  it('edits a template in place and shows the server’s refusal as it is', async () => {
    fetchTemplates.mockResolvedValue(listing({ templates: [row(SALES)] }));
    updateTemplate.mockRejectedValue(new Error('You do not govern demo-pg.salesdb'));
    renderPage();
    const card = await screen.findByRole('article', { name: 'Template Sales' });
    fireEvent.click(within(card).getByRole('button', { name: 'Edit' }));
    const form = screen.getByRole('form', { name: 'Edit Sales' });
    expect(within(form).getByLabelText('Reference label')).toHaveValue('Change ticket');
    fireEvent.click(within(form).getByRole('button', { name: 'Save template' }));
    expect(await within(form).findByRole('alert')).toHaveTextContent('You do not govern demo-pg.salesdb');
    expect(updateTemplate.mock.calls[0][0]).toBe('tpl-sales');
  });
});

describe('problemOf', () => {
  const draft = (form: Partial<RequestForm>, extra: Partial<TemplateDraft> = {}): TemplateDraft => ({
    name: 'x',
    scopeFqn: 'demo-pg.salesdb',
    matchFacets: [],
    form: { ...BUILT_IN_FORM, ...form },
    ...extra,
  });

  it('says what the server would refuse, in the same words', () => {
    expect(problemOf(draft({}), true)).toBeNull();
    expect(problemOf(draft({}, { scopeFqn: null }), true)).toBe('Choose the tables it applies to');
    expect(problemOf(draft({}, { scopeFqn: null }), false)).toBeNull();
    expect(problemOf(draft({ maxDays: 30, durations: [7] }), true)).toContain('choose one');
    expect(problemOf(draft({ maxDays: 30, allowUntilRevoked: false }), true)).toContain('90 is not');
    expect(problemOf(draft({ defaultDays: null, allowUntilRevoked: false }), true)).toBe(
      'Choose the duration the form starts on'
    );
    expect(problemOf(draft({ referenceRequired: true }), true)).toBe(
      'Name the reference before making it required'
    );
    expect(problemOf(draft({ minReasonLength: 0 }), true)).toContain('between 1 and 500');
    expect(problemOf(draft({ durations: [1, 2, 3, 4, 5, 6, 7, 8, 9] }), true)).toBe('Suggest at most 8 durations');
  });

  it('reads durations and purposes the way they are typed', () => {
    expect(parseDurations('90, 7 30,,7')).toEqual([7, 30, 90]);
    expect(parsePurposes(['  Audit ', 'audit', '', 'Report'].join(String.fromCharCode(10)))).toEqual([
      'Audit',
      'Report',
    ]);
  });
});
