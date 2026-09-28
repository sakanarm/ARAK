import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { MemoryRouter, Route, Routes, useLocation } from 'react-router-dom';
import PreauthorizePage, { lengthsFor, targetOf, valuesFor } from './PreauthorizePage';
import type { Vocabulary } from '../../api/governance';
import type { Purpose, PurposeListing } from '../../api/purposes';
import type { RequestTemplate } from '../../api/requestTemplates';

const requestAccess = jest.fn();
const measure = jest.fn();
const fetchAssets = jest.fn();
const fetchTemplate = jest.fn();

jest.mock('../../api/requestTemplates', () => {
  const actual = jest.requireActual('../../api/requestTemplates');
  return { ...actual, fetchEffectiveTemplate: (...args: unknown[]) => fetchTemplate(...args) };
});

jest.mock('../../api/accessRequests', () => {
  const actual = jest.requireActual('../../api/accessRequests');
  return {
    ...actual,
    requestAccess: (...args: unknown[]) => requestAccess(...args),
    measurePreauthorization: (...args: unknown[]) => measure(...args),
  };
});

jest.mock('../../api/client', () => ({
  apiErrorMessage: (error: { message?: string }, fallback: string) => error?.message ?? fallback,
  fetchAssets: (...args: unknown[]) => fetchAssets(...args),
}));

jest.mock('../../api/governance', () => {
  const actual = jest.requireActual('../../api/governance');
  return {
    ...actual,
    fetchVocabulary: () => Promise.reject(new Error('offline')),
    fetchPrincipals: () => Promise.resolve([]),
    fetchAttributeVocabulary: () => Promise.resolve({ keys: [], appRoles: [] }),
  };
});

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
    createdBy: 'system',
    createdAt: '2026-09-28T03:00:00Z',
    updatedBy: 'system',
    updatedAt: '2026-09-28T03:00:00Z',
    ...extra,
  };
}

// Without a register the form asks as it did before there was one.
let register: PurposeListing | undefined;

jest.mock('../../api/purposes', () => ({
  ...jest.requireActual('../../api/purposes'),
  usePurposes: () => ({ data: register, isLoading: false, isError: false }),
}));

const SCOPE = 'demo-pg.salesdb.sales';

const BUILT_IN: RequestTemplate = {
  id: null,
  name: 'Built-in',
  description: null,
  scopeFqn: null,
  matchFacets: [],
  enabled: true,
  form: {
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
  },
};

const PII_FORM: RequestTemplate = {
  ...BUILT_IN,
  id: 'tpl-1',
  name: 'PII under sales',
  form: {
    purposes: ['Fraud analysis', 'Audit'],
    purposeRequired: true,
    durations: [30, 60],
    defaultDays: 30,
    maxDays: 60,
    allowUntilRevoked: false,
    referenceLabel: 'DPIA no.',
    referenceRequired: true,
    minReasonLength: 1,
    guidance: '<b>Name the DPIA</b>',
  },
};

let location = '';
function Where() {
  location = useLocation().pathname;
  return null;
}

function renderPage() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } });
  return render(
    <QueryClientProvider client={client}>
      <MemoryRouter initialEntries={['/requests/preauthorize']}>
        <Routes>
          <Route element={<PreauthorizePage />} path="/requests/preauthorize" />
          <Route element={<p>Sent</p>} path="/requests/:ticket" />
        </Routes>
        <Where />
      </MemoryRouter>
    </QueryClientProvider>
  );
}

async function chooseScope() {
  fireEvent.change(screen.getByLabelText('Search the catalog for a service, database, schema or table'), {
    target: { value: 'sales' },
  });
  fireEvent.click(await screen.findByRole('button', { name: /sales/ }));
}

beforeEach(() => {
  [requestAccess, measure, fetchAssets, fetchTemplate].forEach((fn) => fn.mockReset());
  register = undefined;
  fetchTemplate.mockResolvedValue(BUILT_IN);
  fetchAssets.mockResolvedValue({
    items: [{ id: 'a-1', fqn: SCOPE, name: 'sales', displayName: null, assetType: 'SCHEMA' }],
    total: 1,
  });
  measure.mockResolvedValue({
    scopeFqn: SCOPE,
    scopeType: 'SCHEMA',
    tables: 2,
    tableSample: [`${SCOPE}.customer`, `${SCOPE}.invoice`],
    people: 4,
    peopleAtLeast: false,
    peopleSample: [],
  });
  location = '';
});

describe('targetOf', () => {
  it('is null until there is a facet and somebody to name', () => {
    const tag = [{ facet: 'tags' as const, operator: 'contains' as const, value: ' PII ' }];
    expect(targetOf([], 'GROUP', [{ type: 'team', name: 'Finance' }], [])).toBeNull();
    expect(targetOf(tag, 'GROUP', [{ type: 'team', name: '  ' }], [])).toBeNull();
    expect(targetOf(tag, 'ATTRIBUTE', [{ type: 'team', name: 'Finance' }], [])).toBeNull();
    expect(targetOf(tag, 'GROUP', [{ type: 'team', name: ' Finance ' }], [{ key: 'x', operator: 'eq', value: 'y' }])).toEqual({
      conditions: [{ facet: 'tags', operator: 'contains', value: 'PII' }],
      subject: { kind: 'GROUP', principals: [{ type: 'team', name: 'Finance' }], attributes: [] },
    });
  });
});

describe('lengthsFor', () => {
  it("offers a template's own lengths up to its longest, and starts on its default", () => {
    expect(lengthsFor(PII_FORM.form)).toEqual({ offered: [30, 60], start: 30 });
    expect(lengthsFor({ ...BUILT_IN.form, durations: [], defaultDays: 90 })).toEqual({
      offered: [30, 90, 180, 365],
      start: 90,
    });
    expect(lengthsFor({ ...BUILT_IN.form, durations: [], defaultDays: null, maxDays: 45, allowUntilRevoked: false })).toEqual({
      offered: [30],
      start: 30,
    });
  });
});

describe('valuesFor', () => {
  it('offers classifications at the top and tags beneath them', () => {
    const value = (fqn: string, depth: number, children: Vocabulary['classifications'] = []) => ({
      fqn,
      name: fqn,
      parentFqn: null,
      displayName: null,
      description: null,
      depth,
      provenance: 'openmetadata',
      disabled: false,
      mutuallyExclusive: false,
      assets: 0,
      directAssets: 0,
      policies: 0,
      children,
    });
    const vocabulary: Vocabulary = {
      classifications: [value('PII', 0, [value('PII.Sensitive', 1)]), value('Tier', 0, [value('Tier.Tier1', 1)])],
      glossaries: [],
      domains: [value('Finance', 0, [value('Finance.Risk', 1)])],
      dataProducts: [],
      customProperties: [],
    };
    expect(valuesFor('classifications', vocabulary)).toEqual(['PII', 'Tier']);
    expect(valuesFor('tags', vocabulary)).toEqual(['PII.Sensitive', 'Tier.Tier1']);
    expect(valuesFor('tier', vocabulary)).toEqual(['Tier.Tier1']);
    expect(valuesFor('domains', vocabulary)).toEqual(['Finance', 'Finance.Risk']);
    expect(valuesFor('tags', undefined)).toEqual([]);
  });
});

describe('PreauthorizePage', () => {
  it('counts what it reaches as it is filled in, and sends it as a pre-authorization', async () => {
    requestAccess.mockResolvedValue({ id: 'req-9', ticket: 'REQ-000009' });
    renderPage();

    const send = screen.getByRole('button', { name: 'Send for approval' });
    expect(send).toBeDisabled();
    expect(screen.getByText('Fill in where, which tables and for whom to see.')).toBeInTheDocument();

    await chooseScope();
    fireEvent.change(screen.getByLabelText('Condition 1 value'), { target: { value: 'PII.Sensitive' } });
    fireEvent.change(screen.getByLabelText('Team 1'), { target: { value: 'Finance' } });

    const reach = screen.getByRole('region', { name: 'What it reaches today' });
    expect(await within(reach).findByText('2 tables')).toBeInTheDocument();
    expect(within(reach).getByText('4 people')).toBeInTheDocument();
    expect(within(reach).getByText(`${SCOPE}.invoice`)).toBeInTheDocument();
    expect(measure).toHaveBeenLastCalledWith(SCOPE, {
      conditions: [{ facet: 'tags', operator: 'contains', value: 'PII.Sensitive' }],
      subject: { kind: 'GROUP', principals: [{ type: 'team', name: 'Finance' }], attributes: [] },
    });
    expect(send).toBeDisabled();

    fireEvent.change(screen.getByLabelText('Why they need it'), {
      target: { value: 'The fraud team reads PII under sales every quarter' },
    });
    fireEvent.click(screen.getByRole('button', { name: '180 days' }));
    expect(send).toBeEnabled();
    fireEvent.click(send);

    await waitFor(() =>
      expect(requestAccess).toHaveBeenCalledWith({
        assetFqn: SCOPE,
        sourceId: null,
        reason: 'The fraud team reads PII under sales every quarter',
        purpose: null,
        days: 180,
        attemptedSql: null,
        deniedBy: null,
        kind: 'PREAUTHORIZATION',
        target: {
          conditions: [{ facet: 'tags', operator: 'contains', value: 'PII.Sensitive' }],
          subject: { kind: 'GROUP', principals: [{ type: 'team', name: 'Finance' }], attributes: [] },
        },
      })
    );
    await waitFor(() => expect(location).toBe('/requests/REQ-000009'));
  });

  it('names people by attributes too, and says so when the server refuses', async () => {
    requestAccess.mockRejectedValue(new Error('Choose the service, database, schema or table it is under'));
    renderPage();

    await chooseScope();
    fireEvent.change(screen.getByLabelText('Condition 1 value'), { target: { value: 'Finance' } });
    fireEvent.click(screen.getByRole('button', { name: 'People with attributes' }));
    expect(screen.queryByLabelText('Team 1')).toBeNull();
    fireEvent.change(screen.getByLabelText('Attribute 1'), { target: { value: 'clearance' } });
    fireEvent.change(screen.getByLabelText('Attribute 1 value'), { target: { value: 'L2' } });
    fireEvent.change(screen.getByLabelText('Why they need it'), { target: { value: 'Risk reviews' } });
    fireEvent.click(screen.getByRole('button', { name: 'Until revoked' }));

    fireEvent.click(screen.getByRole('button', { name: 'Send for approval' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('Choose the service');
    expect(requestAccess.mock.calls[0][0]).toMatchObject({
      days: null,
      target: { subject: { kind: 'ATTRIBUTE', principals: [], attributes: [{ key: 'clearance', operator: 'eq', value: 'L2' }] } },
    });
  });

  it('offers the register, and takes the lengths a purpose does not allow off the list', async () => {
    register = {
      purposes: [
        purpose('fraud-analysis', 'Fraud analysis', { maxDays: 14 }),
        purpose('support', 'Support', { status: 'RETIRED' }),
      ],
      canEdit: false,
    };
    requestAccess.mockResolvedValue({ id: 'req-11', ticket: 'REQ-000011' });
    renderPage();

    await chooseScope();
    fireEvent.change(screen.getByLabelText('Condition 1 value'), { target: { value: 'PII.Sensitive' } });
    fireEvent.change(screen.getByLabelText('Team 1'), { target: { value: 'Finance' } });
    fireEvent.change(screen.getByLabelText('Why they need it'), { target: { value: 'Fraud reviews' } });
    expect(screen.getByRole('button', { name: '90 days' })).toHaveAttribute('aria-pressed', 'true');

    fireEvent.click(screen.getByRole('button', { name: /Purpose/ }));
    expect(screen.queryByRole('option', { name: /Support/ })).toBeNull();
    fireEvent.click(await screen.findByRole('option', { name: /^Fraud analysis/ }));

    const durations = screen.getByRole('group', { name: 'Durations' });
    expect(within(durations).queryByRole('button', { name: '90 days' })).toBeNull();
    expect(within(durations).queryByRole('button', { name: 'Until revoked' })).toBeNull();
    expect(within(durations).getByRole('button', { name: '14 days' })).toHaveAttribute('aria-pressed', 'true');
    fireEvent.click(screen.getByRole('button', { name: 'Send for approval' }));

    await waitFor(() =>
      expect(requestAccess.mock.calls[0][0]).toMatchObject({ purpose: 'fraud-analysis', days: 14 })
    );
  });

  it("asks what the scope's request template asks, and sends it", async () => {
    fetchTemplate.mockResolvedValue(PII_FORM);
    requestAccess.mockResolvedValue({ id: 'req-10', ticket: 'REQ-000010' });
    renderPage();

    await chooseScope();
    expect(fetchTemplate).toHaveBeenCalledWith(SCOPE);
    // Guidance is text, never markup.
    expect(await screen.findByRole('note', { name: 'Guidance' })).toHaveTextContent('<b>Name the DPIA</b>');
    expect(screen.queryByRole('button', { name: 'Until revoked' })).toBeNull();
    expect(screen.queryByRole('button', { name: '90 days' })).toBeNull();
    expect(screen.getByRole('button', { name: '30 days' })).toHaveAttribute('aria-pressed', 'true');

    fireEvent.change(screen.getByLabelText('Condition 1 value'), { target: { value: 'PII.Sensitive' } });
    fireEvent.change(screen.getByLabelText('Team 1'), { target: { value: 'Finance' } });
    fireEvent.change(screen.getByLabelText('Why they need it'), { target: { value: 'Quarterly fraud review' } });
    expect(screen.getAllByText('Choose a purpose')).toHaveLength(2);
    const send = screen.getByRole('button', { name: 'Send for approval' });
    expect(send).toBeDisabled();

    fireEvent.click(screen.getByRole('button', { name: /Purpose/ }));
    fireEvent.click(await screen.findByRole('option', { name: 'Fraud analysis' }));
    expect(screen.getByText('Give the DPIA no.')).toBeInTheDocument();
    fireEvent.change(screen.getByLabelText('DPIA no.'), { target: { value: 'DPIA-42' } });
    fireEvent.click(screen.getByRole('button', { name: '60 days' }));
    expect(send).toBeEnabled();
    fireEvent.click(send);

    await waitFor(() =>
      expect(requestAccess.mock.calls[0][0]).toMatchObject({
        kind: 'PREAUTHORIZATION',
        purpose: 'Fraud analysis',
        reference: 'DPIA-42',
        days: 60,
      })
    );
  });

  it('says why the count could not be made', async () => {
    measure.mockRejectedValue(new Error('No catalog asset is called demo-pg.salesdb.sales'));
    renderPage();
    await chooseScope();
    fireEvent.change(screen.getByLabelText('Condition 1 value'), { target: { value: 'PII' } });
    fireEvent.change(screen.getByLabelText('Team 1'), { target: { value: 'Finance' } });
    const reach = screen.getByRole('region', { name: 'What it reaches today' });
    expect(await within(reach).findByRole('alert')).toHaveTextContent('No catalog asset');
  });
});
