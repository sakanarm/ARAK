import { create } from 'zustand';

/**
 * What the assistant and the page it is helping say to each other.
 *
 * The assistant is one component, docked in the shell, and it has to reach
 * whichever page is open — the query console on one route, the policy builder
 * on another. Passing that down through the router would mean every page that
 * never talks to the assistant still carrying a prop for it, so the two ends
 * meet here instead.
 *
 * It is deliberately a postbox and not a conversation. The page publishes what
 * it is pointed at; the assistant leaves a draft; the page picks the draft up
 * and clears it. Nothing here is persisted, because a suggestion that outlives
 * the tab it was made in is a suggestion nobody remembers asking for.
 */

/** Where a draft is being offered, so the panel asks the right question. */
export type AssistMode = 'sql' | 'policy' | 'idle';

/**
 * A draft waiting to be collected.
 *
 * The `at` is not decoration. Somebody who asks the same question twice gets
 * the same text twice, and without something that changes, the page's effect
 * would not fire the second time and the button would look broken.
 */
export interface Handoff {
  text: string;
  at: number;
}

interface AssistState {
  open: boolean;
  /** Which page is listening, published by that page while it is mounted. */
  mode: AssistMode;
  /** The source the query console is pointed at, so the brief is the right one. */
  sourceId: string | null;
  /** Its engine, so the statement is written in the right dialect. */
  engine: string | null;
  sql: Handoff | null;
  policy: Handoff | null;

  setOpen: (open: boolean) => void;
  /** Called by a page on mount and whenever its source changes. */
  offer: (mode: AssistMode, sourceId: string | null, engine: string | null) => void;
  /** Called by a page on unmount, so the panel stops offering what is gone. */
  withdraw: (mode: AssistMode) => void;
  deliverSql: (text: string) => void;
  deliverPolicy: (text: string) => void;
  takeSql: () => void;
  takePolicy: () => void;
}

export const useAssistStore = create<AssistState>((set, get) => ({
  open: false,
  mode: 'idle',
  sourceId: null,
  engine: null,
  sql: null,
  policy: null,

  setOpen: (open) => set({ open }),

  offer: (mode, sourceId, engine) => set({ mode, sourceId, engine }),

  // Only the page that claimed the mode may give it up. Without the check, a
  // page unmounting after the next one has mounted -- which is the order React
  // uses often enough -- would wipe the mode the new page just set.
  withdraw: (mode) => {
    if (get().mode === mode) {
      set({ mode: 'idle', sourceId: null, engine: null });
    }
  },

  deliverSql: (text) => set({ sql: { text, at: Date.now() } }),
  deliverPolicy: (text) => set({ policy: { text, at: Date.now() } }),
  takeSql: () => set({ sql: null }),
  takePolicy: () => set({ policy: null }),
}));
