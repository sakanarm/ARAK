import { useState, type ReactNode } from 'react';
import { Link } from 'react-router-dom';
import { useQuery } from '@tanstack/react-query';
import { fetchPolicies } from '../../api/policies';
import {
  fetchExpressionReference,
  validateExpression,
  type ExpressionVerdict,
  type ReferenceCase,
  type ReferenceExample,
  type ReferenceRoot,
} from '../../api/expressions';

/**
 * The syntax reference for the `expr` half of a subject rule (FR-3.2).
 *
 * One page, because an author writing an expression has one question and it
 * does not survive being split across five. Everything on it is served by the
 * backend out of the engine's jar: the roots, the operators, every worked
 * example and every expression that is refused. The front end contributes the
 * layout and nothing else, so this page cannot describe a language the
 * deployed engine is not running.
 *
 * The examples are checkable rather than merely readable. Each one carries the
 * answer the engine gives for a stated person and table — asserted in the
 * backend build, so an example that stopped being true would fail CI — and the
 * button beside it sends the expression to the live parser. That is the
 * difference between documentation somebody trusts and documentation somebody
 * tries once and stops believing.
 */
export default function ExpressionDocsPage() {
  const { data, isLoading, error } = useQuery({
    queryKey: ['expression-reference'],
    queryFn: fetchExpressionReference,
    // The grammar changes when the service is redeployed, not while somebody
    // is reading about it.
    staleTime: Infinity,
  });

  /*
    Each example is also seeded as a real policy by
    scripts/seed-example-policies.mjs, named after the example's id. Matching
    on that name rather than storing ids here means the page links to whatever
    this deployment actually has: an environment where the seeder was never run
    shows the examples with no links, which is the truth, instead of links to
    policies that do not exist.
  */
  const { data: seeded } = useQuery({
    queryKey: ['expression-reference', 'seeded-policies'],
    queryFn: () => fetchPolicies({ limit: 500 }),
    select: (policies) =>
      new Map(policies.map((policy) => [policy.document.name, policy.id])),
  });

  if (isLoading) {
    return (
      <main className="tw:mx-auto tw:max-w-4xl tw:px-6 tw:py-12">
        <p className="tw:text-sm tw:text-tertiary">Loading the reference…</p>
      </main>
    );
  }

  if (error || !data) {
    return (
      <main className="tw:mx-auto tw:max-w-4xl tw:px-6 tw:py-12">
        <h1 className="tw:text-display-sm tw:font-semibold tw:text-primary">Expression syntax</h1>
        <p className="tw:mt-4 tw:text-sm tw:text-error-primary">
          The reference is served by the policy engine, and the service did not answer. Nothing is
          wrong with your policy — this page simply cannot be shown right now.
        </p>
      </main>
    );
  }

  return (
    <main className="tw:mx-auto tw:max-w-4xl tw:px-6 tw:py-10">
      <header>
        <h1 className="tw:text-display-sm tw:font-semibold tw:text-primary">Expression syntax</h1>
        <p className="tw:mt-3 tw:text-md tw:text-tertiary">{data.summary}</p>
      </header>

      <TryIt />

      <Section title="Three answers, not two" id="truth">
        <p className="tw:text-sm tw:text-secondary">{data.truth}</p>
      </Section>

      <Section title="What an expression can refer to" id="roots">
        <div className="tw:flex tw:flex-col tw:gap-5">
          {data.roots.map((root) => (
            <Root key={root.root} root={root} />
          ))}
        </div>
      </Section>

      <Section title="Operators" id="operators">
        <table className="tw:w-full tw:text-sm">
          <thead>
            <tr className="tw:border-b tw:border-secondary tw:text-left tw:text-tertiary">
              <th className="tw:py-2 tw:pr-4 tw:font-medium">Operator</th>
              <th className="tw:py-2 tw:pr-4 tw:font-medium">Means</th>
              <th className="tw:py-2 tw:font-medium">Note</th>
            </tr>
          </thead>
          <tbody>
            {data.operators.map((operator) => (
              <tr key={operator.op} className="tw:border-b tw:border-secondary tw:align-top">
                <td className="tw:py-2 tw:pr-4">
                  <Code>{operator.op}</Code>
                </td>
                <td className="tw:py-2 tw:pr-4 tw:text-primary">{operator.meaning}</td>
                <td className="tw:py-2 tw:text-tertiary">{operator.note}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </Section>

      <Section title="Worked examples" id="examples">
        <p className="tw:mb-4 tw:text-sm tw:text-tertiary">
          Every example below is run against the real engine on each build, with the person and the
          table shown beside it. If one of them ever stopped giving the answer it claims, the build
          would fail before you read it.
        </p>
        <div className="tw:flex tw:flex-col tw:gap-4">
          {data.examples.map((example) => (
            <Example
              example={example}
              key={example.id}
              policyId={seeded?.get(`example-${example.id}`)}
            />
          ))}
        </div>
      </Section>

      <Section title="What will not be saved" id="rejected">
        <p className="tw:mb-4 tw:text-sm tw:text-tertiary">
          These are refused when the policy is saved, rather than failing quietly later.
        </p>
        <ul className="tw:flex tw:flex-col tw:gap-3">
          {data.rejected.map((bad) => (
            <li key={bad.expression} className="tw:text-sm">
              <Code>{bad.expression}</Code>
              <span className="tw:ml-3 tw:text-tertiary">{bad.why}</span>
            </li>
          ))}
        </ul>
      </Section>
    </main>
  );
}

/** A box for checking an expression against the parser that will judge it. */
function TryIt() {
  const [expression, setExpression] = useState('');
  const [verdict, setVerdict] = useState<ExpressionVerdict | null>(null);
  const [checking, setChecking] = useState(false);

  async function check(next: string) {
    setExpression(next);
    if (!next.trim()) {
      setVerdict(null);
      return;
    }
    setChecking(true);
    try {
      setVerdict(await validateExpression(next));
    } finally {
      setChecking(false);
    }
  }

  return (
    <section className="tw:mt-8 tw:rounded-xl tw:border tw:border-secondary tw:bg-primary tw:p-6">
      <h2 className="tw:text-lg tw:font-medium tw:text-primary">Try one</h2>
      <p className="tw:mt-1 tw:text-sm tw:text-tertiary">
        Checked by the engine's own parser — the same one that runs when a policy is saved.
      </p>
      <textarea
        className="tw:mt-4 tw:w-full tw:rounded-lg tw:border tw:border-secondary tw:bg-primary tw:px-3 tw:py-2 tw:font-mono tw:text-sm tw:text-primary"
        onChange={(event) => void check(event.target.value)}
        placeholder="user.country == asset.prop('dataResidency')"
        rows={2}
        value={expression}
      />
      {checking && <p className="tw:mt-2 tw:text-sm tw:text-tertiary">Checking…</p>}
      {!checking && verdict && <Verdict verdict={verdict} />}
    </section>
  );
}

/**
 * What the parser said, with the warning kept visibly apart from the error.
 *
 * Reported separately because they mean different things to the person
 * reading. One says the policy will not save; the other says it will save,
 * activate, read back exactly as typed, and grant nobody anything.
 */
export function Verdict({ verdict }: { verdict: ExpressionVerdict }) {
  if (!verdict.valid) {
    return <p className="tw:mt-3 tw:text-sm tw:text-error-primary">{verdict.message}</p>;
  }
  return (
    <div className="tw:mt-3 tw:flex tw:flex-col tw:gap-2 tw:text-sm">
      <p className="tw:text-success-primary">Reads correctly.</p>
      {verdict.rowDependent && (
        <p className="tw:text-warning-primary">
          This refers to row data, which a subject rule cannot use. It belongs in a data policy row
          filter.
        </p>
      )}
      {verdict.unknownAttributes.length > 0 && (
        <p className="tw:text-warning-primary">
          Nobody in the directory carries{' '}
          {verdict.unknownAttributes.map((name) => `user.${name}`).join(', ')}. That is not an
          error — the attribute may be arriving later — but until somebody has it, an ALLOW
          carrying this expression grants nothing.
        </p>
      )}
    </div>
  );
}

function Root({ root }: { root: ReferenceRoot }) {
  return (
    <div className="tw:rounded-lg tw:border tw:border-secondary tw:p-4">
      <div className="tw:flex tw:items-baseline tw:gap-3">
        <Code>{root.root}.</Code>
        <span className="tw:text-sm tw:font-medium tw:text-primary">{root.title}</span>
        <span className="tw:text-xs tw:text-tertiary">
          {root.open ? 'open — any name is accepted' : 'fixed list — a typo is refused'}
        </span>
      </div>
      <p className="tw:mt-2 tw:text-sm tw:text-tertiary">{root.note}</p>
      <ul className="tw:mt-3 tw:grid tw:grid-cols-1 tw:gap-2 tw:text-sm sm:tw:grid-cols-2">
        {root.members.map((member) => (
          <li key={member.name}>
            <Code>{member.name}</Code>
            <span className="tw:ml-2 tw:text-tertiary">{member.yields}</span>
            {member.aliases.length > 0 && (
              <span className="tw:ml-2 tw:text-xs tw:text-tertiary">
                also {member.aliases.join(', ')}
              </span>
            )}
          </li>
        ))}
      </ul>
    </div>
  );
}

function Example({
  example,
  policyId,
}: {
  example: ReferenceExample;
  policyId?: string;
}) {
  const [verdict, setVerdict] = useState<ExpressionVerdict | null>(null);

  return (
    <article
      className="tw:rounded-lg tw:border tw:border-secondary tw:p-4"
      id={`example-${example.id}`}>
      <h3 className="tw:text-sm tw:font-medium tw:text-primary">{example.title}</h3>
      <pre className="tw:mt-2 tw:overflow-x-auto tw:rounded-md tw:bg-secondary tw:px-3 tw:py-2 tw:font-mono tw:text-sm tw:text-primary">
        {example.expression}
      </pre>
      <p className="tw:mt-2 tw:text-sm tw:text-tertiary">{example.explanation}</p>

      <ul className="tw:mt-3 tw:flex tw:flex-col tw:gap-1 tw:text-sm">
        {example.cases.map((one) => (
          <li key={one.given} className="tw:flex tw:items-baseline tw:gap-2">
            <Answer expect={one.expect} />
            <span className="tw:text-secondary">{one.given}</span>
          </li>
        ))}
      </ul>

      <div className="tw:mt-3 tw:flex tw:flex-wrap tw:items-center tw:gap-3">
        <button
          className="tw:rounded-md tw:border tw:border-secondary tw:px-3 tw:py-1 tw:text-sm tw:text-secondary"
          onClick={() => void validateExpression(example.expression).then(setVerdict)}
          type="button">
          Check against the engine
        </button>
        {policyId && (
          <Link
            className="tw:text-sm tw:text-brand-secondary tw:underline"
            to={`/policies/${policyId}`}>
            Open this as a policy
          </Link>
        )}
      </div>
      {verdict && <Verdict verdict={verdict} />}
    </article>
  );
}

/** The engine's answer for one case, coloured by what it means for access. */
function Answer({ expect }: { expect: ReferenceCase['expect'] }) {
  const tone =
    expect === 'TRUE'
      ? 'tw:text-success-primary'
      : expect === 'UNKNOWN'
        ? 'tw:text-warning-primary'
        : 'tw:text-tertiary';
  return (
    <span className={`tw:w-32 tw:shrink-0 tw:font-mono tw:text-xs ${tone}`}>{expect}</span>
  );
}

function Section({
  title,
  id,
  children,
}: {
  title: string;
  id: string;
  children: ReactNode;
}) {
  return (
    <section className="tw:mt-10" id={id}>
      <h2 className="tw:mb-4 tw:text-lg tw:font-medium tw:text-primary">{title}</h2>
      {children}
    </section>
  );
}

function Code({ children }: { children: ReactNode }) {
  return (
    <code className="tw:rounded tw:bg-secondary tw:px-1.5 tw:py-0.5 tw:font-mono tw:text-xs tw:text-primary">
      {children}
    </code>
  );
}
