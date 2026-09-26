import { checkAnswers, mergeForms, type RequestForm } from './requestTemplates';

const PLAIN: RequestForm = {
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

const STRICT: RequestForm = {
  purposes: ['Fraud analysis', 'Audit'],
  purposeRequired: true,
  durations: [30, 60],
  defaultDays: 60,
  maxDays: 60,
  allowUntilRevoked: false,
  referenceLabel: 'DPIA no.',
  referenceRequired: true,
  minReasonLength: 20,
  guidance: 'Name the DPIA',
};

describe('mergeForms', () => {
  it('takes the strictest of each', () => {
    const { form, conflicts } = mergeForms([PLAIN, STRICT]);
    expect(conflicts).toEqual([]);
    expect(form).toEqual({
      purposes: ['Fraud analysis', 'Audit'],
      purposeRequired: true,
      durations: [7, 30, 60],
      defaultDays: 30,
      maxDays: 60,
      allowUntilRevoked: false,
      referenceLabel: 'DPIA no.',
      referenceRequired: true,
      minReasonLength: 20,
      guidance: 'Name the DPIA',
    });
  });

  it('answers that pass the merged form pass every table', () => {
    const other: RequestForm = { ...PLAIN, maxDays: 45, referenceLabel: 'Change ticket', purposes: ['AUDIT'] };
    const forms = [PLAIN, STRICT, other];
    const { form } = mergeForms(forms);
    expect(form.purposes).toEqual(['Audit']);
    expect(form.maxDays).toBe(45);
    expect(form.referenceLabel).toBe('DPIA no. / Change ticket');
    const answers = { reason: 'Quarterly fraud review of cards', purpose: 'Audit', days: 45, reference: 'X-1' };
    expect(checkAnswers(form, answers)).toBeNull();
    for (const own of forms) expect(checkAnswers(own, answers)).toBeNull();
    expect(checkAnswers(form, { ...answers, days: 46 })).not.toBeNull();
  });

  it('says so when the tables share no purpose', () => {
    const { form, conflicts } = mergeForms([STRICT, { ...PLAIN, purposes: ['Marketing'] }]);
    expect(form.purposes).toEqual([]);
    expect(conflicts[0]).toMatch(/no purpose in common/);
  });

  it('keeps until revoked only when every table offers it, and says each guidance once', () => {
    expect(mergeForms([PLAIN, { ...PLAIN, defaultDays: null }]).form.defaultDays).toBe(30);
    expect(mergeForms([{ ...PLAIN, defaultDays: null }]).form.defaultDays).toBeNull();
    expect(mergeForms([STRICT, STRICT, { ...PLAIN, guidance: 'Ask your lead' }]).form.guidance).toBe(
      'Name the DPIA\n\nAsk your lead'
    );
  });
});
