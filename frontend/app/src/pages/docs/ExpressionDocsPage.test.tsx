import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { fireEvent, render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import ExpressionDocsPage from './ExpressionDocsPage';

/**
 * The page's job is to show what the engine says, and to link an example to the
 * policy that was seeded from it. Both of those are places where a front end
 * could quietly invent something, so both are asserted here.
 *
 * What is deliberately not asserted is whether any given example is true: that
 * is ExpressionReferenceTest's job on the Java side, where the real evaluator
 * is. A copy of those expectations here would be a second, weaker opinion about
 * the same thing.
 */

const reference = {
  summary: 'One boolean, evaluated per request.',
  truth: 'True, false, and undecidable are three different answers.',
  roots: [
    {
      root: 'user',
      title: 'The person asking',
      open: true,
      note: 'Any attribute name resolves to a lookup.',
      members: [{ name: 'user.country', aliases: ['user.attr("country")'], yields: 'a string' }],
    },
  ],
  operators: [{ op: '&&', meaning: 'and', note: 'Both sides must hold.' }],
  examples: [
    {
      id: 'residency',
      title: 'Keep data in the country it is registered to',
      expression: "user.country == asset.prop('dataResidency')",
      explanation: 'Compares the person against the table.',
      cases: [
        { given: 'a Thai analyst on a Thai table', principal: {}, asset: {}, expect: 'TRUE' },
        { given: 'the table has no residency recorded', principal: {}, asset: {}, expect: 'UNKNOWN' },
      ],
    },
  ],
  rejected: [{ expression: 'user.country =', why: 'It stops half way.' }],
};

const fetchExpressionReference = jest.fn();
const validateExpression = jest.fn();
const fetchPolicies = jest.fn();

jest.mock('../../api/expressions', () => ({
  fetchExpressionReference: (...args: unknown[]) => fetchExpressionReference(...args),
  validateExpression: (...args: unknown[]) => validateExpression(...args),
}));

jest.mock('../../api/policies', () => ({
  fetchPolicies: (...args: unknown[]) => fetchPolicies(...args),
}));

function renderPage() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={client}>
      <MemoryRouter>
        <ExpressionDocsPage />
      </MemoryRouter>
    </QueryClientProvider>
  );
}

beforeEach(() => {
  jest.clearAllMocks();
  fetchExpressionReference.mockResolvedValue(reference);
  fetchPolicies.mockResolvedValue([]);
  validateExpression.mockResolvedValue({
    valid: true,
    message: null,
    position: -1,
    rowDependent: false,
    userAttributes: ['country'],
    unknownAttributes: [],
  });
});

test('shows each example with the answers the engine gives', async () => {
  renderPage();

  expect(
    await screen.findByText('Keep data in the country it is registered to')
  ).toBeInTheDocument();
  expect(screen.getByText("user.country == asset.prop('dataResidency')")).toBeInTheDocument();
  // Including the undecidable one, which is the answer a two-valued page would
  // have had to round to false.
  expect(screen.getByText('UNKNOWN')).toBeInTheDocument();
  expect(screen.getByText('a Thai analyst on a Thai table')).toBeInTheDocument();
});

test('links an example to the policy seeded from it, and only when there is one', async () => {
  fetchPolicies.mockResolvedValue([
    { id: 'p-1', document: { name: 'example-residency' } },
    { id: 'p-2', document: { name: 'something-else' } },
  ]);
  renderPage();

  const link = await screen.findByRole('link', { name: 'Open this as a policy' });
  expect(link).toHaveAttribute('href', '/policies/p-1');
});

test('does not offer a policy link on a deployment where nothing was seeded', async () => {
  renderPage();

  await screen.findByText('Keep data in the country it is registered to');
  expect(screen.queryByRole('link', { name: 'Open this as a policy' })).not.toBeInTheDocument();
});

test('checks a typed expression against the engine rather than judging it here', async () => {
  validateExpression.mockResolvedValue({
    valid: false,
    message: 'Expected an operand at position 14',
    position: 14,
    rowDependent: false,
    userAttributes: [],
    unknownAttributes: [],
  });
  renderPage();

  const box = await screen.findByPlaceholderText("user.country == asset.prop('dataResidency')");
  fireEvent.change(box, { target: { value: 'user.country =' } });

  expect(await screen.findByText('Expected an operand at position 14')).toBeInTheDocument();
  expect(validateExpression).toHaveBeenCalledWith('user.country =');
});

test('warns that an attribute nobody carries grants nothing', async () => {
  validateExpression.mockResolvedValue({
    valid: true,
    message: null,
    position: -1,
    rowDependent: false,
    userAttributes: ['contry'],
    unknownAttributes: ['contry'],
  });
  renderPage();

  const box = await screen.findByPlaceholderText("user.country == asset.prop('dataResidency')");
  fireEvent.change(box, { target: { value: "user.contry == 'TH'" } });

  // The distinction the whole warning exists for: this saves, and then does
  // nothing, which no error message would ever tell the author.
  expect(await screen.findByText(/user.contry/)).toBeInTheDocument();
  expect(screen.getByText('Reads correctly.')).toBeInTheDocument();
});
