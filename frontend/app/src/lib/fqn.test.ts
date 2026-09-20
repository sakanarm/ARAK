import { isAncestor, leaf, segments, shortFqn } from './fqn';

test('a quoted segment containing a dot stays one segment', () => {
  expect(segments('prod-mssql."Sales.DB".dbo')).toEqual([
    'prod-mssql',
    'Sales.DB',
    'dbo',
  ]);
  expect(leaf('prod-mssql."Sales.DB".dbo')).toBe('dbo');
});

test('ancestry is by whole segments, not by string prefix', () => {
  expect(isAncestor('Finance', 'Finance.Risk.Credit')).toBe(true);
  // The classic way to get this wrong: 'Finance Ops' starts with 'Finance'.
  expect(isAncestor('Finance', 'Finance Ops')).toBe(false);
  expect(isAncestor('Finance.Risk', 'Finance')).toBe(false);
  // Not a strict ancestor of itself, or nothing would ever be shown.
  expect(isAncestor('Finance', 'Finance')).toBe(false);
});

test('a chip keeps a short parent and elides a long one', () => {
  expect(shortFqn('PII')).toBe('PII');
  expect(shortFqn('PII.Sensitive')).toBe('PII / Sensitive');
  expect(shortFqn('Finance.Risk.Credit')).toBe('… / Risk / Credit');
  // The case that started this: a three-deep sub-domain whose own ancestors
  // run to forty-odd characters each.
  expect(
    shortFqn(
      'Premium Service Delivery Domain.Premium Service Delivery - IOS-Data Sub Domain.Premium Service Delivery - IOS Data/DTP - Sub Domain'
    )
  ).toBe('… / Premium Service Delivery - IOS Data/DTP - Sub Domain');
});
