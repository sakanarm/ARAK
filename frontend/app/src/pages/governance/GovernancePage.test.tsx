import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import GovernancePage from './GovernancePage';
import type { GovernanceValue, Vocabulary } from '../../api/governance';

const fetchVocabulary = jest.fn();
const fetchVocabularyPermission = jest.fn();
const createClassification = jest.fn();
const createTag = jest.fn();
const updateVocabulary = jest.fn();

jest.mock('../../api/governance', () => ({
  fetchVocabulary: () => fetchVocabulary(),
  fetchVocabularyPermission: () => fetchVocabularyPermission(),
  createClassification: (...args: unknown[]) => createClassification(...args),
  createTag: (...args: unknown[]) => createTag(...args),
  updateVocabulary: (...args: unknown[]) => updateVocabulary(...args),
}));
jest.mock('../../api/client', () => ({
  apiErrorMessage: (error: unknown, fallback: string) =>
    (error as { message?: string })?.message ?? fallback,
}));

function value(fqn: string, overrides: Partial<GovernanceValue> = {}): GovernanceValue {
  const dot = fqn.lastIndexOf('.');
  return {
    fqn,
    name: dot < 0 ? fqn : fqn.slice(dot + 1),
    parentFqn: dot < 0 ? null : fqn.slice(0, dot),
    displayName: null,
    description: null,
    depth: 0,
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

const VOCABULARY: Vocabulary = {
  classifications: [
    value('PII', { children: [value('PII.Sensitive', { description: 'Personal data' })] }),
    value('Retention', {
      provenance: 'local',
      description: 'How long we keep it',
      children: [value('Retention.Short', { provenance: 'local', description: 'Short-term' })],
    }),
  ],
  glossaries: [value('Finance', { children: [value('Finance.Revenue')] })],
  domains: [],
  dataProducts: [],
  customProperties: [],
};

function renderPage(path = '/governance') {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  render(
    <QueryClientProvider client={client}>
      <MemoryRouter initialEntries={[path]}>
        <GovernancePage />
      </MemoryRouter>
    </QueryClientProvider>
  );
}

beforeEach(() => {
  jest.resetAllMocks();
  fetchVocabulary.mockResolvedValue(VOCABULARY);
  fetchVocabularyPermission.mockResolvedValue({ canEdit: true });
});

describe('GovernancePage, vocabulary made in ARAK', () => {
  it('shows a reader what was made here, and offers them nothing to change', async () => {
    fetchVocabularyPermission.mockResolvedValue({ canEdit: false });
    renderPage();

    expect(await screen.findByText('Retention')).toBeInTheDocument();
    expect(screen.getAllByText('made in ARAK')).toHaveLength(2);
    await waitFor(() => expect(fetchVocabularyPermission).toHaveBeenCalled());
    expect(screen.queryByRole('button', { name: 'New classification' })).toBeNull();
    expect(screen.queryByRole('button', { name: /Add a tag under/ })).toBeNull();
    expect(screen.queryByRole('button', { name: /^Edit / })).toBeNull();
  });

  it('makes a classification, and refuses a name with a dot before asking the server', async () => {
    createClassification.mockResolvedValue({ kind: 'CLASSIFICATION', fqn: 'Legal' });
    renderPage();
    fireEvent.click(await screen.findByRole('button', { name: 'New classification' }));

    const create = screen.getByRole('button', { name: 'Create' });
    expect(create).toBeDisabled();
    fireEvent.change(screen.getByRole('textbox', { name: 'Name' }), { target: { value: 'Legal.Hold' } });
    fireEvent.change(screen.getByRole('textbox', { name: 'Description' }), {
      target: { value: ' Kept for a legal hold ' },
    });
    expect(screen.getByText('A name cannot contain a dot or a double quote.')).toBeInTheDocument();
    expect(create).toBeDisabled();

    fireEvent.change(screen.getByRole('textbox', { name: 'Name' }), { target: { value: 'Legal' } });
    fireEvent.click(screen.getByRole('checkbox', { name: /One tag per column or table/ }));
    fireEvent.click(create);

    await waitFor(() => expect(createClassification).toHaveBeenCalled());
    expect(createClassification.mock.calls[0][0]).toEqual({
      name: 'Legal',
      displayName: undefined,
      description: 'Kept for a legal hold',
      mutuallyExclusive: true,
    });
    expect(await screen.findByRole('status')).toHaveTextContent('Made Legal.');
    // The list is read again, so the new classification shows without a reload.
    await waitFor(() => expect(fetchVocabulary).toHaveBeenCalledTimes(2));
    expect(screen.queryByRole('form', { name: 'New classification' })).toBeNull();
  });

  it("adds a tag under OpenMetadata's classification, where Edit tags can then find it", async () => {
    createTag.mockResolvedValue({ kind: 'TAG', fqn: 'PII.Payroll' });
    renderPage();
    fireEvent.click(await screen.findByRole('button', { name: 'Add a tag under PII' }));

    expect(screen.getByRole('form', { name: 'New tag under PII' })).toBeInTheDocument();
    fireEvent.change(screen.getByRole('textbox', { name: 'Name' }), { target: { value: 'Payroll' } });
    expect(screen.getByText(/It becomes PII\.Payroll/)).toBeInTheDocument();
    fireEvent.change(screen.getByRole('textbox', { name: 'Description' }), {
      target: { value: 'Pay and bank details' },
    });
    fireEvent.click(screen.getByRole('button', { name: 'Create' }));

    await waitFor(() => expect(createTag).toHaveBeenCalled());
    expect(createTag.mock.calls[0][0]).toEqual({
      classificationFqn: 'PII',
      name: 'Payroll',
      displayName: undefined,
      description: 'Pay and bank details',
    });
    expect(await screen.findByRole('status')).toHaveTextContent(/Made PII\.Payroll\..*Edit tags/);
  });

  it("changes only what was made here, and disabling says what happens to what is attached", async () => {
    updateVocabulary.mockResolvedValue({ kind: 'TAG', fqn: 'Retention.Short' });
    renderPage();
    await screen.findByText('Retention');

    // OpenMetadata's values are changed in OpenMetadata.
    expect(screen.queryByRole('button', { name: 'Edit PII' })).toBeNull();
    expect(screen.queryByRole('button', { name: 'Edit Sensitive' })).toBeNull();
    expect(screen.getByRole('button', { name: 'Edit Retention' })).toBeInTheDocument();

    fireEvent.click(screen.getByRole('button', { name: 'Edit Short' }));
    expect(screen.getByRole('textbox', { name: 'Description' })).toHaveValue('Short-term');
    expect(screen.queryByRole('textbox', { name: 'Name' })).toBeNull();
    fireEvent.click(screen.getByRole('checkbox', { name: /Disabled: no longer offered/ }));
    expect(screen.getByText(/it stays, and so does any mask it brings/)).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: 'Save' }));

    await waitFor(() => expect(updateVocabulary).toHaveBeenCalled());
    expect(updateVocabulary.mock.calls[0][0]).toEqual({
      kind: 'TAG',
      fqn: 'Retention.Short',
      displayName: '',
      description: 'Short-term',
      disabled: true,
    });
    expect(await screen.findByRole('status')).toHaveTextContent('Saved Retention.Short.');
  });

  it('says why the server refused, and keeps what was typed', async () => {
    createTag.mockRejectedValue(new Error('PII.Payroll already exists'));
    renderPage();
    fireEvent.click(await screen.findByRole('button', { name: 'Add a tag under PII' }));
    fireEvent.change(screen.getByRole('textbox', { name: 'Name' }), { target: { value: 'Payroll' } });
    fireEvent.change(screen.getByRole('textbox', { name: 'Description' }), { target: { value: 'Pay' } });
    fireEvent.click(screen.getByRole('button', { name: 'Create' }));

    expect(await screen.findByRole('alert')).toHaveTextContent('already exists');
    expect(screen.getByRole('textbox', { name: 'Name' })).toHaveValue('Payroll');
  });

  it('offers no editing on the other headings, which only OpenMetadata fills', async () => {
    renderPage('/governance?tab=glossaries');
    expect(await screen.findByText('Finance')).toBeInTheDocument();
    await waitFor(() => expect(fetchVocabularyPermission).toHaveBeenCalled());
    expect(screen.queryByRole('button', { name: 'New classification' })).toBeNull();
    expect(screen.queryByRole('button', { name: /Add a tag under/ })).toBeNull();
  });
});
