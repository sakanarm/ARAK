import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { GrantDialog } from './GrantDialog';
import type { Principal } from '../../api/governance';
import type { Purpose, PurposeListing } from '../../api/purposes';

const fetchPrincipals = jest.fn();

jest.mock('../../api/governance', () => ({
  fetchPrincipals: (...args: unknown[]) => fetchPrincipals(...args),
}));

let register: PurposeListing | undefined;

jest.mock('../../api/purposes', () => ({
  ...jest.requireActual('../../api/purposes'),
  usePurposes: () => ({ data: register, isLoading: false, isError: false }),
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
    createdBy: 'system',
    createdAt: '2026-09-28T03:00:00Z',
    updatedBy: 'system',
    updatedAt: '2026-09-28T03:00:00Z',
    ...extra,
  };
}

const CAPPED = purpose('fraud-analysis', 'Fraud analysis', { maxDays: 30 });
const OPEN = purpose('reporting', 'Reporting');

function principal(overrides: Partial<Principal> = {}): Principal {
  return {
    id: '11111111-1111-1111-1111-111111111111',
    principalType: 'USER',
    username: 'analyst_a',
    email: 'analyst_a@example.com',
    displayName: 'Analyst A',
    source: 'local',
    enabled: true,
    attributeCount: 3,
    memberCount: 0,
    groupCount: 0,
    groups: [],
    ...overrides,
  } as Principal;
}

/** Renders the dialog open, with one person to pick, and returns the spy. */
async function openDialog() {
  const onSubmit = jest.fn();
  fetchPrincipals.mockResolvedValue([principal()]);

  const client = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  });
  render(
    <QueryClientProvider client={client}>
      <GrantDialog
        assetFqn="demo-pg.salesdb.sales.customer"
        error={null}
        isOpen
        onClose={jest.fn()}
        onSubmit={onSubmit}
        submitting={false}
      />
    </QueryClientProvider>
  );

  await screen.findByText('Analyst A');
  fireEvent.click(screen.getByText('Analyst A'));
  fireEvent.change(screen.getByLabelText('Why'), {
    target: { value: 'Quarter-end reconciliation' },
  });
  return onSubmit;
}

const grantButton = () => screen.getByText('Grant access', { selector: 'span' });

/** Picks a purpose by the name the register gives it. */
async function choosePurpose(name: string) {
  fireEvent.click(screen.getByRole('button', { name: /Purpose/ }));
  fireEvent.click(await screen.findByRole('option', { name: new RegExp(`^${name}`) }));
}

describe('GrantDialog', () => {
  beforeEach(() => {
    jest.clearAllMocks();
    register = undefined;
  });

  it('sends the typed number of days rather than only the chips', async () => {
    const onSubmit = await openDialog();

    fireEvent.change(screen.getByLabelText('Number of days'), {
      target: { value: '45' },
    });
    fireEvent.click(grantButton());

    await waitFor(() => expect(onSubmit).toHaveBeenCalled());
    const sent = onSubmit.mock.calls[0][0];
    expect(sent.validFrom).toBeNull();
    // No purpose chosen is none sent, not an empty key the server would look up.
    expect(sent.purpose).toBeNull();

    // 45 days out, to the minute. Asserting the exact instant would assert the
    // clock; asserting the gap is what the field actually promises.
    const gapDays =
      (new Date(sent.validUntil).getTime() - Date.now()) / 86_400_000;
    expect(gapDays).toBeGreaterThan(44.9);
    expect(gapDays).toBeLessThan(45.1);
  });

  it('refuses a duration of zero instead of quietly making it open-ended', async () => {
    await openDialog();

    fireEvent.change(screen.getByLabelText('Number of days'), {
      target: { value: '0' },
    });

    expect(
      screen.getByText('Give a number of days above zero, or pick No expiry.')
    ).toBeInTheDocument();
    // The old helper returned null for 0, which the API reads as "forever":
    // the widest possible grant from the narrowest possible typo.
    expect(grantButton().closest('button')).toBeDisabled();
  });

  it('still honours the chips, including No expiry', async () => {
    const onSubmit = await openDialog();

    fireEvent.click(screen.getByText('No expiry'));
    fireEvent.click(grantButton());

    await waitFor(() => expect(onSubmit).toHaveBeenCalled());
    expect(onSubmit.mock.calls[0][0].validUntil).toBeNull();
  });

  it('sends both instants when the author schedules the window', async () => {
    const onSubmit = await openDialog();

    fireEvent.click(screen.getByText('Set start and end dates'));
    fireEvent.change(screen.getByLabelText('Starts'), {
      target: { value: '2027-01-04T09:00' },
    });
    fireEvent.change(screen.getByLabelText('Ends'), {
      target: { value: '2027-02-01T18:00' },
    });
    fireEvent.click(grantButton());

    await waitFor(() => expect(onSubmit).toHaveBeenCalled());
    const sent = onSubmit.mock.calls[0][0];
    expect(new Date(sent.validFrom).getTime()).toBe(
      new Date('2027-01-04T09:00').getTime()
    );
    expect(new Date(sent.validUntil).getTime()).toBe(
      new Date('2027-02-01T18:00').getTime()
    );
  });

  it('says a future-dated grant is not live yet', async () => {
    await openDialog();

    fireEvent.click(screen.getByText('Set start and end dates'));
    fireEvent.change(screen.getByLabelText('Starts'), {
      target: { value: '2027-01-04T09:00' },
    });

    // The one thing somebody scheduling ahead can get wrong without noticing is
    // believing the grant works the moment they press the button.
    expect(
      screen.getByText(/until then the grant exists and grants nothing/)
    ).toBeInTheDocument();
  });

  it('blocks an end before the start, the way GrantStore would', async () => {
    await openDialog();

    fireEvent.click(screen.getByText('Set start and end dates'));
    fireEvent.change(screen.getByLabelText('Starts'), {
      target: { value: '2027-02-01T09:00' },
    });
    fireEvent.change(screen.getByLabelText('Ends'), {
      target: { value: '2027-01-04T09:00' },
    });

    expect(
      screen.getByText('The end has to come after the start.')
    ).toBeInTheDocument();
    expect(grantButton().closest('button')).toBeDisabled();
  });

  it('treats an equal start and end as a refusal, not a zero-length grant', async () => {
    await openDialog();

    fireEvent.click(screen.getByText('Set start and end dates'));
    fireEvent.change(screen.getByLabelText('Starts'), {
      target: { value: '2027-02-01T09:00' },
    });
    fireEvent.change(screen.getByLabelText('Ends'), {
      target: { value: '2027-02-01T09:00' },
    });

    // GrantStore uses isAfter, not !isBefore, so the server refuses this too.
    expect(
      screen.getByText('The end has to come after the start.')
    ).toBeInTheDocument();
  });

  it('leaves an open-ended scheduled grant open-ended', async () => {
    const onSubmit = await openDialog();

    fireEvent.click(screen.getByText('Set start and end dates'));
    fireEvent.change(screen.getByLabelText('Starts'), {
      target: { value: '2027-01-04T09:00' },
    });
    fireEvent.click(grantButton());

    await waitFor(() => expect(onSubmit).toHaveBeenCalled());
    const sent = onSubmit.mock.calls[0][0];
    expect(sent.validFrom).not.toBeNull();
    expect(sent.validUntil).toBeNull();
  });

  it('keeps refusing to submit without a reason', async () => {
    await openDialog();

    fireEvent.change(screen.getByLabelText('Why'), { target: { value: '   ' } });

    // Whitespace, because the server treats a blank reason as no reason and a
    // form that sends it collects a 400 instead of a grant.
    expect(grantButton().closest('button')).toBeDisabled();
  });
});

describe('GrantDialog with a purpose', () => {
  beforeEach(() => {
    jest.clearAllMocks();
    register = { purposes: [OPEN, CAPPED], canEdit: false };
  });

  it('sends the register key of the purpose chosen', async () => {
    const onSubmit = await openDialog();

    await choosePurpose('Reporting');
    fireEvent.click(grantButton());

    await waitFor(() => expect(onSubmit).toHaveBeenCalled());
    expect(onSubmit.mock.calls[0][0].purpose).toBe('reporting');
  });

  it('pulls the countdown within a purpose’s longest access, and stops offering forever', async () => {
    await openDialog();
    fireEvent.change(screen.getByLabelText('Number of days'), { target: { value: '45' } });

    await choosePurpose('Fraud analysis');

    expect(screen.getByLabelText('Number of days')).toHaveValue(30);
    expect(
      screen.getByText(
        'Access for Fraud analysis lasts at most 30 days, so the grant has to end within that.'
      )
    ).toBeInTheDocument();
    expect(screen.queryByText('No expiry')).toBeNull();
    expect(screen.queryByText('90 days')).toBeNull();
    expect(screen.getByText('30 days')).toBeInTheDocument();
    expect(grantButton().closest('button')).toBeEnabled();
  });

  it('keeps a shorter countdown as typed', async () => {
    await openDialog();
    fireEvent.change(screen.getByLabelText('Number of days'), { target: { value: '12' } });

    await choosePurpose('Fraud analysis');

    expect(screen.getByLabelText('Number of days')).toHaveValue(12);
  });

  it('refuses more days than the purpose allows, and no days at all', async () => {
    await openDialog();
    await choosePurpose('Fraud analysis');

    fireEvent.change(screen.getByLabelText('Number of days'), { target: { value: '31' } });
    expect(screen.getByText('Access for Fraud analysis lasts at most 30 days.')).toBeInTheDocument();
    expect(grantButton().closest('button')).toBeDisabled();

    // Cleared, which without a purpose reads as No expiry.
    fireEvent.change(screen.getByLabelText('Number of days'), { target: { value: '' } });
    expect(
      screen.getByText('Access for Fraud analysis lasts at most 30 days; choose a number of days.')
    ).toBeInTheDocument();
    expect(grantButton().closest('button')).toBeDisabled();

    fireEvent.change(screen.getByLabelText('Number of days'), { target: { value: '0' } });
    // No expiry is not on offer, so the hint does not point at it.
    expect(screen.getByText('Give a number of days above zero.')).toBeInTheDocument();
  });

  it('asks for an end within the purpose when the dates are typed', async () => {
    const onSubmit = await openDialog();
    await choosePurpose('Fraud analysis');

    fireEvent.click(screen.getByText('Set start and end dates'));
    fireEvent.change(screen.getByLabelText('Starts'), {
      target: { value: '2027-01-04T09:00' },
    });
    expect(
      screen.getByText('Access for Fraud analysis lasts at most 30 days; give the grant an end.')
    ).toBeInTheDocument();
    expect(grantButton().closest('button')).toBeDisabled();

    // Counted from the start, not from today: 31 days after it is too long.
    fireEvent.change(screen.getByLabelText('Ends'), {
      target: { value: '2027-02-04T09:00' },
    });
    expect(screen.getByText(/lasts at most 30 days; end it by/)).toBeInTheDocument();
    expect(grantButton().closest('button')).toBeDisabled();

    fireEvent.change(screen.getByLabelText('Ends'), {
      target: { value: '2027-02-03T09:00' },
    });
    fireEvent.click(grantButton());

    await waitFor(() => expect(onSubmit).toHaveBeenCalled());
    const sent = onSubmit.mock.calls[0][0];
    expect(sent.purpose).toBe('fraud-analysis');
    expect(new Date(sent.validUntil).getTime()).toBe(new Date('2027-02-03T09:00').getTime());
  });

  it('brings the countdown back within the purpose when switching from dates', async () => {
    await openDialog();
    fireEvent.change(screen.getByLabelText('Number of days'), { target: { value: '' } });
    fireEvent.click(screen.getByText('Set start and end dates'));

    await choosePurpose('Fraud analysis');
    fireEvent.click(screen.getByText('Use a duration'));

    // The empty countdown the dates left behind would otherwise come back as a
    // refusal the person never caused.
    expect(screen.getByLabelText('Number of days')).toHaveValue(30);
    expect(grantButton().closest('button')).toBeEnabled();
  });
});
