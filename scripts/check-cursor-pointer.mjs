#!/usr/bin/env node
/**
 * Tailwind 4's preflight sets `cursor: default` on <button>, so a button that
 * is not a form control looks unclickable unless it says `tw:cursor-pointer`
 * for itself. This has been reported from the screen three separate times, so
 * it is worth a check rather than another round of finding them by hand.
 *
 *   node scripts/check-cursor-pointer.mjs
 *
 * Exits non-zero and lists the offenders. A button whose class comes from a
 * helper (`className={chip(active)}`) is reported too -- put the class in the
 * helper and the count drops.
 */
import { readdirSync, readFileSync, statSync } from 'node:fs';
import { join, relative } from 'node:path';
import { fileURLToPath } from 'node:url';

// fileURLToPath, not URL.pathname: the repo lives under a path with spaces and
// pathname hands them back percent-encoded.
const ROOT = fileURLToPath(new URL('../frontend/app/src', import.meta.url));

/** Buttons we have looked at and accepted. */
const ALLOWED = new Set([
  // A <button> written inside a JSX comment explaining why the real control
  // is a react-aria Button instead.
  'layout/TopNav.tsx:<button>',
  // The class is built by the chip() helper further down the same file, which
  // carries tw:cursor-pointer for every call site.
  'pages/catalog/GrantDialog.tsx:<button aria-pressed={days === option.value} className={chip(days === option.value)}',
  // The home editor's four icon buttons take their class from ICON_BUTTON at
  // the top of the same file, which carries tw:cursor-pointer and
  // tw:disabled:cursor-not-allowed for every call site.
  'pages/home/HomeEditor.tsx:<button aria-label="Move up" className={ICON_BUTTON}',
  'pages/home/HomeEditor.tsx:<button aria-label="Move down" className={ICON_BUTTON}',
  'pages/home/HomeEditor.tsx:<button aria-label={`Remove ${spec.label}`} className={ICON_BUTTON}',
  'pages/home/HomeEditor.tsx:<button aria-label={`Remove link ${index + 1}`} className={ICON_BUTTON}',
]);

function walk(dir) {
  const out = [];
  for (const name of readdirSync(dir)) {
    const full = join(dir, name);
    if (statSync(full).isDirectory()) {
      if (name !== 'node_modules') out.push(...walk(full));
    } else if (name.endsWith('.tsx')) {
      out.push(full);
    }
  }
  return out;
}

/** The opening tag, brace-aware so `className={`a ${b}`}` does not cut short. */
function openingTag(source, start) {
  let depth = 0;
  let i = start;
  while (i < source.length) {
    const c = source[i];
    if (c === '{') depth += 1;
    else if (c === '}') depth -= 1;
    else if (c === '>' && depth === 0) break;
    i += 1;
  }
  return source.slice(start, i + 1);
}

const offenders = [];
for (const file of walk(ROOT)) {
  const source = readFileSync(file, 'utf8');
  const rel = relative(ROOT, file).split('\\').join('/');
  for (const match of source.matchAll(/<button\b/g)) {
    const tag = openingTag(source, match.index);
    if (tag.includes('cursor-pointer')) continue;
    const flat = tag.replace(/\s+/g, ' ').trim();
    if ([...ALLOWED].some((a) => `${rel}:${flat}`.startsWith(a))) continue;
    const line = source.slice(0, match.index).split('\n').length;
    offenders.push(`${rel}:${line}  ${flat.slice(0, 120)}`);
  }
}

if (offenders.length > 0) {
  console.error(
    `${offenders.length} button(s) are missing tw:cursor-pointer, so they show ` +
      'an arrow instead of a hand:\n'
  );
  for (const line of offenders) console.error(`  ${line}`);
  console.error(
    '\nAdd tw:cursor-pointer to the className (and tw:disabled:cursor-default ' +
      'if the button can be disabled).'
  );
  process.exit(1);
}

console.log('every <button> offers a hand');
