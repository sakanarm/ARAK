import PolicyBuilderPage from '../PolicyBuilderPage';
import { DATA_ACCESS_POLICY } from './dataAccessPolicy';

/** A new data access policy, at /policies/new/data. */
export default function DataAccessPolicyPage() {
  return <PolicyBuilderPage kind={DATA_ACCESS_POLICY} />;
}
