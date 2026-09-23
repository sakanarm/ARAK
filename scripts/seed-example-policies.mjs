#!/usr/bin/env node
/**
 * Turns every worked example on the syntax page into a real policy (FR-3.2).
 *
 * The examples are read from the running service, not from a copy kept here,
 * so this script cannot seed a language the engine is not running. Whatever
 * `GET /v1/expressions/reference` returns is what gets written, and an example
 * added to the reference appears here on the next run with no edit to this
 * file.
 *
 * Each one lands as a DRAFT bound to the demo table. Draft is the point, not a
 * shortcut: eleven ACTIVE subscription policies on one table compose by
 * intersection (FR-5.1), so activating them all at once would deny everybody
 * and demonstrate nothing. As drafts they are real rows -- readable, versioned,
 * bound -- and the per-policy impact analysis runs each one through the engine
 * on its own, which is what makes a single example demonstrable. Activating any
 * of them is one click on the lifecycle control.
 *
 * Idempotent: a second run updates the policies it wrote the first time rather
 * than failing on the (name, environment) unique key.
 *
 * Usage:
 *   node scripts/seed-example-policies.mjs
 *   ARAK_URL=http://localhost:8090/Arak/api node scripts/seed-example-policies.mjs
 */

const BASE = (process.env.ARAK_URL ?? 'http://localhost:8080/api').replace(/\/$/, '');
const USER = process.env.ARAK_ADMIN_USER ?? 'admin';
const PASSWORD = process.env.ARAK_ADMIN_PASSWORD ?? process.env.IDENTITY_BOOTSTRAP_ADMIN_PASSWORD;
const TABLE = process.env.ARAK_DEMO_TABLE ?? 'demo-pg.salesdb.sales.customer';

if (!PASSWORD) {
  console.error(
    'Set ARAK_ADMIN_PASSWORD (or IDENTITY_BOOTSTRAP_ADMIN_PASSWORD) — the seeder signs in as a policy author.',
  );
  process.exit(2);
}

let token = null;

async function call(method, path, body) {
  const response = await fetch(`${BASE}${path}`, {
    method,
    headers: {
      'content-type': 'application/json',
      ...(token ? { authorization: `Bearer ${token}` } : {}),
    },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  const text = await response.text();
  if (!response.ok) {
    throw new Error(`${method} ${path} -> ${response.status} ${text.slice(0, 400)}`);
  }
  return text ? JSON.parse(text) : null;
}

/**
 * The selector that pins a policy to the demo table.
 *
 * `eq` on the physical table rather than a facet condition: an example exists
 * to show one expression deciding, and a facet selector would drag whatever
 * else carries that tag into the demo.
 */
function onDemoTable() {
  return { condition: { facet: 'table', operator: 'eq', value: TABLE } };
}

/** The cases the reference documents, written where a reader will find them. */
function describe(example) {
  const lines = [
    example.explanation,
    '',
    'Worked answers from the syntax reference:',
    ...example.cases.map((one) => `  ${one.expect.padEnd(14)} ${one.given}`),
    '',
    'Seeded by scripts/seed-example-policies.mjs from GET /v1/expressions/reference.',
  ];
  return lines.join('\n');
}

/**
 * A row-dependent expression becomes the thing the page says it should become.
 *
 * `row.branch_code == user.branch` is refused as a subject rule on purpose --
 * a subscription decision happens before any row exists -- and the reference
 * says so. Seeding it as a row filter instead is that advice carried out, and
 * the demo gets to show both halves: the refusal and the shape that works.
 */
function asRowFilter(expression) {
  const match = expression.match(/^\s*row\.(\w+)\s*==\s*user\.(\w+)\s*$/);
  if (!match) {
    return null;
  }
  return {
    rowFilters: [
      {
        kind: 'ATTRIBUTE_COMPARE',
        column: match[1],
        operator: 'eq',
        userAttribute: match[2],
      },
    ],
  };
}

function subscriptionPolicy(example) {
  return {
    name: `example-${example.id}`,
    displayName: `Example — ${example.title}`,
    description: describe(example),
    policyType: 'SUBSCRIPTION',
    scopeLevel: 'TABLE',
    scopeFqn: TABLE,
    selector: onDemoTable(),
    subject: { expression: example.expression },
    effect: 'ALLOW',
  };
}

function dataPolicy(example, data) {
  return {
    name: `example-${example.id}`,
    displayName: `Example — ${example.title}`,
    description: `${describe(example)}\n\nA subject rule cannot read row data, so this example is seeded as a row filter, which is where the reference says it belongs.`,
    policyType: 'DATA',
    scopeLevel: 'TABLE',
    scopeFqn: TABLE,
    selector: onDemoTable(),
    data,
  };
}

async function main() {
  const login = await call('POST', '/v1/auth/login', { username: USER, password: PASSWORD });
  token = login.accessToken;

  const reference = await call('GET', '/v1/expressions/reference');
  const existing = new Map(
    (await call('GET', '/v1/policies?limit=500')).map((row) => [row.document.name, row]),
  );

  const results = [];
  for (const example of reference.examples) {
    const verdict = await call('POST', '/v1/expressions/validate', {
      expression: example.expression,
    });

    let document;
    if (!verdict.valid) {
      results.push([example.id, 'skipped', verdict.message]);
      continue;
    } else if (verdict.rowDependent) {
      const data = asRowFilter(example.expression);
      if (!data) {
        results.push([example.id, 'skipped', 'row-dependent, and not a shape the seeder can turn into a filter']);
        continue;
      }
      document = dataPolicy(example, data);
    } else {
      document = subscriptionPolicy(example);
    }

    const prior = existing.get(document.name);
    let stored;
    if (prior) {
      stored = await call(
        'PUT',
        `/v1/policies/${prior.id}?version=${prior.version}&reason=${encodeURIComponent('re-seeded from the syntax reference')}`,
        document,
      );
      results.push([example.id, 'updated', stored.id]);
    } else {
      stored = await call('POST', '/v1/policies', document);
      results.push([example.id, 'created', stored.id]);
    }

    // Bound at creation already; re-resolved here so a re-run after the demo
    // table moved does not leave a policy pointing at nothing.
    await call('POST', `/v1/policies/${stored.id}/bindings/resolve`);
  }

  const width = Math.max(...results.map(([id]) => id.length));
  for (const [id, what, detail] of results) {
    console.log(`${id.padEnd(width)}  ${what.padEnd(8)}  ${detail}`);
  }
  console.log(`\n${results.length} examples, on ${TABLE}. Open them under Policies, state DRAFT.`);
}

main().catch((error) => {
  console.error(error.message);
  process.exit(1);
});
