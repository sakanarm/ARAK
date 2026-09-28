import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { fireEvent, render, screen } from '@testing-library/react';
import type { ReactNode } from 'react';
import type { Purpose, PurposeListing } from '../../api/purposes';
import type { RequestForm } from '../../api/requestTemplates';
import {
  cappedBy,
  fitDays,
  purposeDaysProblem,
  purposeHint,
  PurposeLabel,
  purposeOptions,
  RequestPurpose,
  startingPurpose,
} from './purposePickers';

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

const FRAUD = purpose('fraud-analysis', 'Fraud analysis', {
  legalBasis: 'LEGITIMATE_INTEREST',
  sensitiveAllowed: true,
  maxDays: 30,
});
const REPORTING = purpose('reporting', 'Reporting');
const SUPPORT = purpose('support', 'Support', { status: 'RETIRED', legalBasis: 'CONTRACT' });
const REGISTER = [REPORTING, SUPPORT, FRAUD];

let register: PurposeListing | undefined;

jest.mock('../../api/purposes', () => ({
  ...jest.requireActual('../../api/purposes'),
  usePurposes: () => ({ data: register, isLoading: false, isError: false }),
}));

const FORM: RequestForm = {
  purposes: [],
  purposeRequired: false,
  durations: [7, 30, 90],
  defaultDays: null,
  maxDays: null,
  allowUntilRevoked: true,
  referenceLabel: null,
  referenceRequired: false,
  minReasonLength: 1,
  guidance: null,
};

function renderWith(node: ReactNode) {
  const client = new QueryClient();
  return render(<QueryClientProvider client={client}>{node}</QueryClientProvider>);
}

beforeEach(() => {
  register = { purposes: REGISTER, canEdit: false };
});

describe('purposeOptions', () => {
  it('offers the register’s purposes in use by name, and keeps a current value it lacks', () => {
    expect(purposeOptions(REGISTER)).toEqual([
      { value: 'fraud-analysis', label: 'Fraud analysis', hint: 'Legitimate interest · sensitive data allowed · at most 30 days' },
      { value: 'reporting', label: 'Reporting', hint: undefined },
    ]);
    expect(purposeOptions(REGISTER, 'REPORTING')).toHaveLength(2);
    expect(purposeOptions(REGISTER, 'support').at(-1)).toEqual({
      value: 'support',
      label: 'Support (retired)',
      hint: 'Retired; choose another',
    });
    expect(purposeOptions(REGISTER, 'Marketing').at(-1)).toEqual({
      value: 'Marketing',
      label: 'Marketing',
      hint: 'Not in the register',
    });
    expect(purposeOptions(undefined)).toEqual([]);
  });

  it('says nothing more of a purpose that commits to nothing', () => {
    expect(purposeHint(REPORTING)).toBeUndefined();
    expect(purposeHint(SUPPORT)).toBe('Contract');
  });
});

describe('startingPurpose', () => {
  it('takes a template’s own spelling, or the register’s key while it is in use', () => {
    const listed = { ...FORM, purposes: ['Fraud investigation', 'Regulatory report'] };
    expect(startingPurpose(listed, REGISTER, ' fraud INVESTIGATION ')).toBe('Fraud investigation');
    expect(startingPurpose(listed, REGISTER, 'reporting')).toBe('');
    expect(startingPurpose(FORM, REGISTER, 'Fraud-Analysis')).toBe('fraud-analysis');
    expect(startingPurpose(FORM, REGISTER, 'support')).toBe('');
    expect(startingPurpose(FORM, REGISTER, 'Marketing')).toBe('');
    expect(startingPurpose(FORM, undefined, 'fraud-analysis')).toBe('');
    expect(startingPurpose(FORM, REGISTER, null)).toBe('');
  });
});

describe('the longest access a purpose allows', () => {
  it('folds into the form: shorter lengths only, and never until revoked', () => {
    expect(cappedBy(FORM, REPORTING)).toBe(FORM);
    expect(cappedBy(FORM, undefined)).toBe(FORM);
    expect(cappedBy(FORM, FRAUD)).toMatchObject({
      maxDays: 30,
      allowUntilRevoked: false,
      durations: [7, 30],
      defaultDays: 30,
    });
    // A template's own tighter limit stands.
    expect(cappedBy({ ...FORM, maxDays: 14, defaultDays: 7 }, FRAUD)).toMatchObject({
      maxDays: 14,
      durations: [7],
      defaultDays: 7,
    });
  });

  it('brings the days typed within it', () => {
    expect(fitDays('', FRAUD)).toBe('30');
    expect(fitDays('45', FRAUD)).toBe('30');
    expect(fitDays('7', FRAUD)).toBe('7');
    expect(fitDays('', REPORTING)).toBe('');
    expect(fitDays('400', undefined)).toBe('400');
  });

  it('refuses what outlasts it in the server’s words', () => {
    expect(purposeDaysProblem(FRAUD, 45)).toBe('Access for Fraud analysis lasts at most 30 days');
    expect(purposeDaysProblem(FRAUD, null)).toBe(
      'Access for Fraud analysis lasts at most 30 days; choose a number of days'
    );
    expect(purposeDaysProblem(FRAUD, 30)).toBeNull();
    expect(purposeDaysProblem(REPORTING, null)).toBeNull();
    expect(purposeDaysProblem(undefined, 400)).toBeNull();
  });
});

describe('RequestPurpose', () => {
  it('offers the register where the form lists nothing, with no purpose allowed', async () => {
    const onChange = jest.fn();
    renderWith(<RequestPurpose form={FORM} onChange={onChange} value="" />);

    fireEvent.click(screen.getByRole('button', { name: /Purpose/ }));
    expect(await screen.findByRole('option', { name: /^No particular purpose/ })).toBeInTheDocument();
    expect(screen.queryByRole('option', { name: /Support/ })).toBeNull();
    fireEvent.click(screen.getByRole('option', { name: /^Fraud analysis/ }));
    expect(onChange).toHaveBeenCalledWith('fraud-analysis');
  });

  it('says how long access for the chosen purpose lasts', () => {
    renderWith(<RequestPurpose form={FORM} onChange={jest.fn()} value="fraud-analysis" />);
    expect(screen.getByText('Access for this purpose lasts at most 30 days.')).toBeInTheDocument();
  });

  it('offers only what a template lists, by the register’s names, and none it retired', async () => {
    const onChange = jest.fn();
    const form = { ...FORM, purposes: ['fraud-analysis', 'support', 'Regulatory report'], purposeRequired: true };
    renderWith(<RequestPurpose form={form} onChange={onChange} value="" />);

    fireEvent.click(screen.getByRole('button', { name: /Purpose/ }));
    expect(await screen.findByRole('option', { name: /^Fraud analysis/ })).toBeInTheDocument();
    expect(screen.queryByRole('option', { name: /No particular purpose/ })).toBeNull();
    expect(screen.queryByRole('option', { name: /Support/ })).toBeNull();
    expect(screen.queryByRole('option', { name: /^Reporting/ })).toBeNull();
    fireEvent.click(screen.getByRole('option', { name: /^Regulatory report/ }));
    expect(onChange).toHaveBeenCalledWith('Regulatory report');
  });

  it('asks as before without a register: typed where required, not at all where not', () => {
    register = undefined;
    const { container, unmount } = renderWith(<RequestPurpose form={FORM} onChange={jest.fn()} value="" />);
    expect(container).toBeEmptyDOMElement();
    unmount();

    const onChange = jest.fn();
    renderWith(<RequestPurpose form={{ ...FORM, purposeRequired: true }} onChange={onChange} value="" />);
    fireEvent.change(screen.getByLabelText('Purpose'), { target: { value: 'Audit' } });
    expect(onChange).toHaveBeenCalledWith('Audit');
  });
});

describe('PurposeLabel', () => {
  it('names a stored purpose as the register does, with what it rests on', () => {
    renderWith(<PurposeLabel value="FRAUD-ANALYSIS" />);
    expect(screen.getByText('Fraud analysis')).toBeInTheDocument();
    expect(screen.getByText('Legitimate interest')).toBeInTheDocument();
    expect(screen.getByText('Sensitive allowed')).toBeInTheDocument();
  });

  it('marks a retired one, and shows a value the register lacks as it was stored', () => {
    renderWith(
      <>
        <PurposeLabel value="support" />
        <PurposeLabel value="Month-end close" />
      </>
    );
    expect(screen.getByText('Support')).toBeInTheDocument();
    expect(screen.getByText('Retired')).toBeInTheDocument();
    expect(screen.getByText('Month-end close')).toBeInTheDocument();
  });
});
