import PolicyBuilderPage from '../PolicyBuilderPage';
import { SUBSCRIPTION_POLICY } from './subscriptionPolicy';

/** A new subscription policy, at /policies/new/subscription. */
export default function SubscriptionPolicyPage() {
  return <PolicyBuilderPage kind={SUBSCRIPTION_POLICY} />;
}
