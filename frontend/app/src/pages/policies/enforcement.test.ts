import { capabilities } from './enforcement';
import type { Policy } from '../../generated/entity/policy/policy';

const MASKING = {
  data: { columnRules: [{ action: 'MASK' }] },
} as unknown as Policy;

function note(policy: Policy, engine: string, mode: string) {
  return capabilities(policy, engine).find((entry) => entry.mode === mode);
}

describe('what each enforcement mode can carry', () => {
  it('says a mode that does not exist on MySQL is not available, whatever the policy holds', () => {
    for (const policy of [{} as Policy, MASKING]) {
      expect(note(policy, 'MYSQL', 'SECURE_VIEW')).toMatchObject({ support: 'none' });
      expect(note(policy, 'MYSQL', 'SECURE_VIEW')?.gaps).toEqual([
        expect.stringContaining('not available on MySQL'),
      ]);
      expect(note(policy, 'MYSQL', 'NATIVE_CONFIG')).toMatchObject({ support: 'none' });
      // The one mode MySQL has carries the whole policy.
      expect(note(policy, 'MYSQL', 'PROXY')).toEqual({ mode: 'PROXY', support: 'full', gaps: [] });
    }
  });

  it('leaves the engines that have every mode as they were', () => {
    expect(capabilities({} as Policy, 'POSTGRES').map((entry) => entry.support)).toEqual([
      'full',
      'full',
      'full',
    ]);
    expect(note(MASKING, 'POSTGRES', 'NATIVE_CONFIG')?.gaps).toEqual([
      expect.stringContaining('no column masking in core'),
    ]);
    expect(note(MASKING, 'SQLSERVER', 'SECURE_VIEW')).toMatchObject({ support: 'full', gaps: [] });
  });

  it('reports an engine nobody has written up as a gap rather than as working', () => {
    expect(note(MASKING, 'ORACLE', 'NATIVE_CONFIG')?.gaps).toEqual([
      expect.stringContaining('has not been verified'),
    ]);
  });
});
