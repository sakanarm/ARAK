#!/usr/bin/env node
/*
 * JSON Schema -> TypeScript.
 *
 * The other half of the pipeline whose Java half is jsonschema2pojo in
 * backend/dac-spec. Both read the SAME files under
 * backend/dac-spec/src/main/resources/json/schema, which is the whole point:
 * the Policy Builder and the engine cannot disagree about what a policy is,
 * because neither of them owns the definition.
 *
 * Named after OpenMetadata's own `yarn parse-schema` so anyone moving between
 * the two repositories reaches for the right command.
 *
 * Output goes to src/generated/ and is gitignored. Do not edit it; edit the
 * schema.
 *
 * On the barrel: json-schema-to-typescript inlines every $ref-ed definition
 * into each file that reaches it, so FacetCondition ends up declared in four of
 * the six outputs. `export *` across all six is therefore ambiguous and does
 * not compile. The barrel below re-exports each name from the first module that
 * declares it, walking type/ before entity/ before api/ so a name comes from
 * the schema that owns it. Two modules declaring the same name with different
 * bodies is a real conflict and fails the build rather than picking one.
 */

import { compileFromFile } from 'json-schema-to-typescript';
import { mkdir, readdir, writeFile, rm } from 'node:fs/promises';
import { dirname, join, relative, resolve, sep } from 'node:path';
import { fileURLToPath } from 'node:url';

const here = dirname(fileURLToPath(import.meta.url));
const schemaRoot = resolve(here, '../../../backend/dac-spec/src/main/resources/json/schema');
const outRoot = resolve(here, '../src/generated');

async function findSchemas(dir) {
  const found = [];
  for (const entry of await readdir(dir, { withFileTypes: true })) {
    const full = join(dir, entry.name);
    if (entry.isDirectory()) {
      found.push(...(await findSchemas(full)));
    } else if (entry.name.endsWith('.json')) {
      found.push(full);
    }
  }
  return found;
}

/**
 * Top-level exported declarations of a generated module, name -> { kind, text }.
 * Text runs to the start of the next export, which is enough to compare two
 * inlined copies of the same definition.
 */
function extractDeclarations(ts) {
  const decls = new Map();
  const re = /^export\s+(?:declare\s+)?(interface|type|enum|const)\s+([A-Za-z0-9_$]+)/gm;
  const marks = [];
  let m;
  while ((m = re.exec(ts)) !== null) {
    marks.push({ kind: m[1], name: m[2], start: m.index });
  }
  for (let i = 0; i < marks.length; i += 1) {
    const end = i + 1 < marks.length ? marks[i + 1].start : ts.length;
    decls.set(marks[i].name, { kind: marks[i].kind, text: ts.slice(marks[i].start, end).trim() });
  }
  return decls;
}

/** Comments and whitespace differ harmlessly between inlined copies. */
function stripNoise(text) {
  return text
    .replace(/\/\*[\s\S]*?\*\//g, '')
    .replace(/\/\/.*$/gm, '')
    .replace(/\s+/g, ' ')
    .trim();
}

/**
 * json-schema-to-typescript appends a counter when one file inlines the same
 * definition more than once: a recursive AssetSelector becomes AssetSelector
 * plus an identical AssetSelector1, and which of the two a field points at
 * depends on traversal order. Comparing raw text across modules would flag that
 * as a conflict, so fold every Name<digits> back onto Name when the module also
 * declares Name with the same body. What is left after folding is a real
 * disagreement between two schemas.
 */
function aliasMap(decls) {
  const aliases = new Map();
  for (const name of decls.keys()) {
    const m = /^(.+?)\d+$/.exec(name);
    // A module declaring both Foo and Foo1 is the generator counting, not an
    // author naming two types one apart.
    if (m && decls.has(m[1])) aliases.set(name, m[1]);
  }
  return aliases;
}

/**
 * Scalar type aliases (`type FacetType = 'tags' | ...`) that a module declares.
 * Only the module holding the schema that defines them gets the named alias;
 * a module that $refs the definition across files gets the union inlined at the
 * use site instead. Expanding the alias before comparing puts both spellings in
 * the same shape. Anything with a brace is an object type, which the generator
 * never inlines this way, so it is left alone.
 */
function scalarAliases(decls) {
  const expansions = new Map();
  for (const [name, decl] of decls) {
    if (decl.kind !== 'type') continue;
    const m = /^export\s+type\s+[A-Za-z0-9_$]+\s*=\s*([\s\S]+?);?$/.exec(stripNoise(decl.text));
    if (m && !m[1].includes('{')) expansions.set(name, m[1].trim());
  }
  return expansions;
}

function normalize(text, aliases, expansions) {
  let out = stripNoise(text);
  for (const [alias, base] of aliases) {
    out = out.replace(new RegExp(`\\b${alias}\\b`, 'g'), base);
  }
  // Two passes: an alias may expand to a union naming another alias.
  for (let pass = 0; pass < 2; pass += 1) {
    for (const [name, rhs] of expansions) {
      out = out.replace(new RegExp(`(?<!type )\\b${name}\\b(?! =)`, 'g'), rhs);
    }
  }
  return out.replace(/\s+/g, ' ').trim();
}

/** type/ owns the shared vocabulary, entity/ builds on it, api/ sits on top. */
const AREA_ORDER = ['type/', 'entity/', 'api/'];
function areaRank(id) {
  const i = AREA_ORDER.findIndex((prefix) => id.startsWith(prefix));
  return i === -1 ? AREA_ORDER.length : i;
}

async function main() {
  const schemas = (await findSchemas(schemaRoot)).sort();
  if (schemas.length === 0) {
    throw new Error(`No schemas under ${schemaRoot}`);
  }

  // Regenerate from scratch: a type left behind after its schema was deleted is
  // worse than no type at all, because it keeps compiling.
  await rm(outRoot, { recursive: true, force: true });

  const modules = [];
  for (const schema of schemas) {
    const rel = relative(schemaRoot, schema).split(sep).join('/');
    const id = rel.replace(/\.json$/, '');
    const out = join(outRoot, `${id}.ts`);
    await mkdir(dirname(out), { recursive: true });

    const ts = await compileFromFile(schema, {
      cwd: dirname(schema),
      bannerComment: [
        '/* eslint-disable */',
        '/**',
        ` * Generated from ${rel} by scripts/parse-schema.mjs.`,
        ' * Do not edit. Change the JSON Schema in backend/dac-spec and run: yarn parse-schema',
        ' */',
      ].join('\n'),
      additionalProperties: false,
      declareExternallyReferenced: true,
      unreachableDefinitions: true,
      style: { singleQuote: true },
    });

    await writeFile(out, ts, 'utf8');
    const decls = extractDeclarations(ts);
    modules.push({ id, decls, aliases: aliasMap(decls), expansions: scalarAliases(decls) });
    process.stdout.write(`  ${id}.ts\n`);
  }

  modules.sort((a, b) => areaRank(a.id) - areaRank(b.id) || a.id.localeCompare(b.id));

  const owner = new Map(); // exported name -> { module, kind, text }
  const reexports = new Map(modules.map((mod) => [mod.id, { type: [], value: [] }]));
  const conflicts = [];
  let collapsed = 0;

  for (const mod of modules) {
    for (const [name, decl] of mod.decls) {
      // Counter-suffixed aliases are an artefact of inlining, never part of the
      // schema vocabulary; they stay in their module and out of the barrel.
      if (mod.aliases.has(name)) continue;

      const body = normalize(decl.text, mod.aliases, mod.expansions);
      const held = owner.get(name);
      if (!held) {
        owner.set(name, { module: mod.id, kind: decl.kind, body });
        const bucket = decl.kind === 'const' || decl.kind === 'enum' ? 'value' : 'type';
        reexports.get(mod.id)[bucket].push(name);
      } else if (held.body !== body) {
        conflicts.push(`  ${name}: ${held.module} and ${mod.id} declare it differently`);
      } else {
        collapsed += 1;
      }
    }
  }

  if (conflicts.length > 0) {
    throw new Error(
      [
        'Two schemas define the same type name with different shapes:',
        ...conflicts,
        'Rename one, or move the shared definition into type/ and $ref it from both.',
      ].join('\n')
    );
  }

  const lines = [];
  for (const mod of modules) {
    const { type, value } = reexports.get(mod.id);
    if (value.length > 0) {
      lines.push(`export { ${value.sort().join(', ')} } from './${mod.id}';`);
    }
    // `export type` is required here: tsconfig sets isolatedModules.
    if (type.length > 0) {
      lines.push(`export type { ${type.sort().join(', ')} } from './${mod.id}';`);
    }
  }

  await writeFile(
    join(outRoot, 'index.ts'),
    [
      '/* eslint-disable */',
      '/**',
      ' * Generated by scripts/parse-schema.mjs. Do not edit.',
      ' *',
      ' * Each name is re-exported from the module that declares it first, walking',
      ' * type/ then entity/ then api/. Inlined duplicates are dropped here; they are',
      ' * still present in their own module if you import from it directly.',
      ' */',
      ...lines,
      '',
    ].join('\n'),
    'utf8'
  );

  process.stdout.write(
    `\n${schemas.length} schema(s) -> src/generated ` +
      `(${owner.size} types, ${collapsed} inlined duplicate(s) collapsed)\n`
  );
}

main().catch((err) => {
  process.stderr.write(`parse-schema failed: ${err.stack ?? err}\n`);
  process.exit(1);
});
