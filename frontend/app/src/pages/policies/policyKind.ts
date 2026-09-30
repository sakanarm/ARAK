import type { ComponentType, ReactNode } from 'react';
import type { AttributeVocabulary, Principal, Vocabulary } from '../../api/governance';
import type { Policy } from '../../generated/entity/policy/policy';

/**
 * What a kind of policy brings to the builder.
 *
 * The builder (PolicyBuilderPage) is the part every policy shares: the name,
 * where it sits, which assets it covers, saving, the lifecycle, NokRak and the
 * rail. Everything after that belongs to the kind, and lives in the kind's own
 * folder -- subscription/ and data-access/ -- so the two can be worked on
 * separately without touching the same file.
 */

export type PolicyType = Policy['policyType'];

/** Where the builder opens for a new policy of each kind. */
export const NEW_POLICY_PATH: Record<PolicyType, string> = {
  SUBSCRIPTION: '/policies/new/subscription',
  DATA: '/policies/new/data',
};

/** One step of the form after the shared ones; the builder numbers it. */
export interface PolicyStep {
  title: string;
  description: string;
  body: ReactNode;
}

/** What a kind's steps are given to read and write the document. */
export interface PolicyStepContext {
  draft: Policy;
  patch: (next: Partial<Policy>) => void;
  vocabulary?: Vocabulary;
  attributes?: AttributeVocabulary;
  principals?: Principal[];
}

export interface PolicyKind {
  type: PolicyType;
  /** The page title while a new one is written. */
  title: string;
  icon: ComponentType<{ className?: string }>;
  /** Background and text classes of the icon's tile. */
  tone: string;
  /** Laid over the empty document when a new one opens. */
  initial: Partial<Policy>;
  steps: (context: PolicyStepContext) => PolicyStep[];
}
