import { EyeOff } from '@untitledui/icons';
import type { PolicyKind } from '../policyKind';
import SubjectBuilder from '../SubjectBuilder';
import DataPolicyBuilder from './DataPolicyBuilder';

/**
 * A data access policy: what somebody who got in sees inside the table.
 *
 * The steps after "Which assets it covers" are this file's; the builder shows
 * them for every data policy, new or stored.
 */
export const DATA_ACCESS_POLICY: PolicyKind = {
  type: 'DATA',
  title: 'Data policy',
  icon: EyeOff,
  tone: 'tw:bg-utility-purple-50 tw:text-utility-purple-600',
  // Opens on the organisation, where masking by tag is written once and
  // covers everything.
  initial: { policyType: 'DATA', scopeLevel: 'ORG' },
  steps: ({ draft, patch, attributes, principals, vocabulary }) => [
    {
      title: 'Who it is about',
      description:
        'Optional. Leave it empty and the restrictions below apply to everyone who gets past the subscription policies.',
      body: (
        <SubjectBuilder
          attributes={attributes}
          onChange={(next) => patch({ subject: next })}
          principals={principals}
          value={draft.subject}
        />
      ),
    },
    {
      title: 'What they see',
      description:
        'Row filters and column rules — the RLS and masking half of the policy.',
      body: (
        <DataPolicyBuilder
          attributes={attributes}
          onChange={(next) => patch({ data: next })}
          value={draft.data}
          vocabulary={vocabulary}
        />
      ),
    },
  ],
};
