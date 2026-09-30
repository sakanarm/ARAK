import { Key01 } from '@untitledui/icons';
import type { Policy } from '../../../generated/entity/policy/policy';
import { Field, Select } from '../controls';
import type { PolicyKind } from '../policyKind';
import SubjectBuilder from '../SubjectBuilder';

/**
 * A subscription policy: who reaches the table at all.
 *
 * The steps after "Which assets it covers" are this file's; the builder shows
 * them for every subscription policy, new or stored.
 */
export const SUBSCRIPTION_POLICY: PolicyKind = {
  type: 'SUBSCRIPTION',
  title: 'Subscription policy',
  icon: Key01,
  tone: 'tw:bg-utility-brand-50 tw:text-utility-brand-600',
  initial: { policyType: 'SUBSCRIPTION' },
  steps: ({ draft, patch, attributes, principals }) => [
    {
      title: 'Who it is about',
      description:
        'Roles, attributes, an expression across both sides, and the hours it holds — all ANDed into one predicate.',
      body: (
        <>
          <div className="tw:mb-5">
            <Field
              hint="A deny always beats an allow, anywhere in the stack, and no match at all is already a deny."
              label="Effect">
              <Select
                className="tw:w-56"
                onChange={(next) => patch({ effect: next as Policy['effect'] })}
                options={[
                  { value: 'ALLOW', label: 'Allow' },
                  { value: 'DENY', label: 'Deny' },
                ]}
                value={draft.effect ?? 'ALLOW'}
              />
            </Field>
          </div>
          <SubjectBuilder
            attributes={attributes}
            onChange={(next) => patch({ subject: next })}
            principals={principals}
            value={draft.subject}
          />
        </>
      ),
    },
  ],
};
