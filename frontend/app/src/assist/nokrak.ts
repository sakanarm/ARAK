import type { AssistMode } from './assistStore';

/**
 * NokRak (น้องรักษ์) -- the assistant's name, and the lines it says from the
 * corner.
 *
 * <p>Short, and always true to what it can actually do on the page it is on:
 * a line offering to write SQL on a page with no query console is a promise
 * the button then breaks. Everywhere else it only says hello and where to find
 * it. None of them claim it can run, approve or switch anything on -- it drafts,
 * and a person decides.
 */
export const NOKRAK = { name: 'NokRak', thai: 'น้องรักษ์' } as const;

const ANYWHERE = [
  'Need help? Ask me!',
  "Hi, I'm NokRak. Stuck on something?",
  'สวัสดีครับ น้องรักษ์เองนะ มีอะไรให้ช่วยไหม?',
  'Open a query or a policy and I can lend a hand.',
  'I draft, you decide. That is the deal.',
];

const BY_MODE: Record<Exclude<AssistMode, 'idle'>, string[]> = {
  sql: [
    'Tell me what you want to know. I will write the SQL.',
    'Not sure how to phrase the query? Ask me in plain words.',
    'ถามเป็นภาษาคนได้เลย เดี๋ยวน้องรักษ์เขียน SQL ให้',
    'Your query still goes through every policy. I only help write it.',
  ],
  policy: [
    'Describe the rule in a sentence. I will sketch the policy.',
    'Who should see what? Tell me and I will draft it.',
    'เล่าเงื่อนไขมาได้เลย เดี๋ยวร่าง policy ให้ดู',
    'I only draft. Nothing switches on until you save it.',
  ],
};

/** A line for this page. `random` is injectable so a test can pin it. */
export function pickLine(mode: AssistMode, random: () => number = Math.random): string {
  const pool = mode === 'idle' ? ANYWHERE : [...BY_MODE[mode], ...ANYWHERE.slice(0, 2)];
  const index = Math.min(pool.length - 1, Math.floor(random() * pool.length));
  return pool[index];
}

/** Every line it can say in this mode -- for tests and nothing else. */
export function linesFor(mode: AssistMode): string[] {
  return mode === 'idle' ? [...ANYWHERE] : [...BY_MODE[mode], ...ANYWHERE.slice(0, 2)];
}
