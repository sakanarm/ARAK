import { linesFor, pickLine } from './nokrak';

describe('NokRak lines', () => {
  it('picks from the start and the end of the pool without running off it', () => {
    const lines = linesFor('sql');
    expect(pickLine('sql', () => 0)).toBe(lines[0]);
    expect(pickLine('sql', () => 0.999999)).toBe(lines[lines.length - 1]);
    // Math.random never returns 1, but a pinned stub can; it must not be undefined.
    expect(pickLine('sql', () => 1)).toBe(lines[lines.length - 1]);
  });

  it('only offers SQL where there is a query console to write it into', () => {
    for (const line of linesFor('idle')) {
      expect(line).not.toMatch(/SQL/);
    }
    for (const line of linesFor('policy')) {
      expect(line).not.toMatch(/SQL/);
    }
    expect(linesFor('sql').some((line) => line.includes('SQL'))).toBe(true);
  });

  it('never claims to run, approve or activate anything', () => {
    for (const mode of ['idle', 'sql', 'policy'] as const) {
      for (const line of linesFor(mode)) {
        expect(line).not.toMatch(/\b(I will|I'll) (run|approve|activate|apply|grant)\b/i);
      }
    }
  });
});
