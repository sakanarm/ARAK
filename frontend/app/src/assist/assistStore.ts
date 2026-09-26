import { create } from 'zustand';
import type { ChatCard } from '../api/llm';

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
 *
 * <p>The chat (M28) keeps its lines here too, so closing the panel or moving to
 * another page does not lose the conversation -- but only for as long as the
 * tab is open, for the same reason.
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
  /** The source a statement was written for, when the chat knew it. */
  sourceId?: string | null;
}

/**
 * One line of the conversation. `error` lines are shown and never sent back to
 * the model: they are the console talking, not the assistant.
 */
export interface ChatEntry {
  role: 'user' | 'assistant' | 'error';
  text: string;
  cards?: ChatCard[];
  model?: string;
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
  chat: ChatEntry[];
  /** A question asked from somewhere else, for the panel to send. */
  ask: Handoff | null;

  setOpen: (open: boolean) => void;
  /** Called by a page on mount and whenever its source changes. */
  offer: (mode: AssistMode, sourceId: string | null, engine: string | null) => void;
  /** Called by a page on unmount, so the panel stops offering what is gone. */
  withdraw: (mode: AssistMode) => void;
  deliverSql: (text: string, sourceId?: string | null) => void;
  deliverPolicy: (text: string) => void;
  takeSql: () => void;
  takePolicy: () => void;
  addChat: (entry: ChatEntry) => void;
  clearChat: () => void;
  /** Opens the panel on the chat with this question, from search or a page. */
  askArak: (question: string) => void;
  takeAsk: () => void;
}

export const useAssistStore = create<AssistState>((set, get) => ({
  open: false,
  mode: 'idle',
  sourceId: null,
  engine: null,
  sql: null,
  policy: null,
  chat: [],
  ask: null,

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

  deliverSql: (text, sourceId) => set({ sql: { text, at: Date.now(), sourceId } }),
  deliverPolicy: (text) => set({ policy: { text, at: Date.now() } }),
  takeSql: () => set({ sql: null }),
  takePolicy: () => set({ policy: null }),
  // The last few dozen lines are plenty for a panel this size; the server only
  // sends the model the most recent of them anyway.
  addChat: (entry) => set({ chat: [...get().chat, entry].slice(-60) }),
  clearChat: () => set({ chat: [] }),
  askArak: (question) => {
    const text = question.trim();
    if (text) {
      set({ open: true, ask: { text, at: Date.now() } });
    }
  },
  takeAsk: () => set({ ask: null }),
}));
