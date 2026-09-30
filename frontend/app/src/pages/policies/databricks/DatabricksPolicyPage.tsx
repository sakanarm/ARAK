import { Link, useSearchParams } from 'react-router-dom';
import { ArrowLeft, CheckCircle, Tool02 } from '@untitledui/icons';
import { Chip as Badge } from '../../../components/chips';
import type { Policy } from '../../../generated/entity/policy/policy';
import EngineMark from '../EngineMark';

/**
 * Where a new Databricks policy is configured.
 *
 * Databricks is governed differently from the databases the policy form is
 * built around, so it gets a builder of its own, and "Where it runs" sends
 * Databricks here instead of opening that form. The builder is being written
 * separately; until it lands this page holds its place, and saves nothing.
 *
 * To plug the builder in, render it in place of <BuilderPending />. It receives
 * the kind of policy chosen on "Where it runs" (?kind=SUBSCRIPTION|DATA).
 */

type PolicyKind = Policy['policyType'];

/** The address "Where it runs" opens for a Databricks policy of this kind. */
export function databricksPath(kind: PolicyKind): string {
  return `/policies/new/databricks?kind=${kind}`;
}

export default function DatabricksPolicyPage() {
  const [params] = useSearchParams();
  const kind: PolicyKind = params.get('kind') === 'DATA' ? 'DATA' : 'SUBSCRIPTION';

  return (
    <>
      <Link
        className="tw:inline-flex tw:items-center tw:gap-1 tw:text-sm tw:text-tertiary tw:hover:text-primary"
        to={`/policies/new?kind=${kind}`}>
        <ArrowLeft className="tw:size-4" />
        Where it runs
      </Link>

      <header className="tw:mt-4 tw:rounded-xl tw:border tw:border-secondary tw:bg-primary tw:p-5 tw:shadow-xs">
        <div className="tw:flex tw:items-center tw:gap-3">
          <EngineMark id="DATABRICKS" label="Databricks" />
          <div className="tw:min-w-0">
            <h1 className="tw:text-xl tw:font-semibold tw:text-primary">New Databricks policy</h1>
            <p className="tw:mt-0.5 tw:text-sm tw:text-tertiary">
              Databricks policies are set up in a builder of their own, not in the form
              the other databases use.
            </p>
          </div>
        </div>
        <ol aria-label="Progress" className="tw:mt-4 tw:flex tw:flex-wrap tw:items-center tw:gap-2 tw:text-sm">
          <li className="tw:flex tw:items-center tw:gap-2 tw:text-tertiary">
            <CheckCircle className="tw:size-6 tw:text-brand-secondary" />
            Where it runs
          </li>
          <li aria-hidden className="tw:h-px tw:w-8 tw:bg-border-secondary" />
          <li aria-current="step" className="tw:flex tw:items-center tw:gap-2 tw:font-medium tw:text-primary">
            <span className="tw:flex tw:size-6 tw:items-center tw:justify-center tw:rounded-full tw:bg-brand-solid tw:text-xs tw:font-semibold tw:text-white">
              2
            </span>
            Configure on Databricks
          </li>
        </ol>
        <div className="tw:mt-4 tw:flex tw:flex-wrap tw:gap-1.5" data-testid="databricks-target">
          <Badge color="brand" size="sm" type="pill-color">
            {kind === 'DATA' ? 'Data policy' : 'Subscription policy'}
          </Badge>
          <Badge color="gray" size="sm" type="pill-color">
            Databricks
          </Badge>
        </div>
      </header>

      <div className="tw:mt-6">
        <BuilderPending kind={kind} />
      </div>
    </>
  );
}

/** Holds the builder's place until it is plugged in. */
function BuilderPending({ kind }: { kind: PolicyKind }) {
  return (
    <section
      aria-label="Databricks builder"
      className="tw:flex tw:flex-col tw:items-center tw:rounded-xl tw:border tw:border-dashed tw:border-primary tw:bg-secondary tw:px-6 tw:py-12 tw:text-center">
      <span className="tw:flex tw:size-12 tw:items-center tw:justify-center tw:rounded-full tw:bg-utility-orange-50 tw:text-utility-orange-700">
        <Tool02 className="tw:size-6" />
      </span>
      <h2 className="tw:mt-4 tw:text-md tw:font-semibold tw:text-primary">
        The Databricks builder is on its way
      </h2>
      <p className="tw:mt-1 tw:max-w-md tw:text-sm tw:text-tertiary">
        This is where a {kind === 'DATA' ? 'data' : 'subscription'} policy for Databricks
        will be configured. Nothing can be written or saved here yet.
      </p>
      <div className="tw:mt-5 tw:flex tw:flex-wrap tw:justify-center tw:gap-3">
        <Link
          className="tw:rounded-lg tw:border tw:border-primary tw:bg-primary tw:px-3.5 tw:py-2 tw:text-sm tw:font-semibold tw:text-secondary tw:shadow-xs tw:hover:bg-primary_hover"
          to={`/policies/new?kind=${kind}`}>
          Choose another database
        </Link>
        <Link
          className="tw:rounded-lg tw:px-3.5 tw:py-2 tw:text-sm tw:font-semibold tw:text-brand-secondary tw:hover:underline"
          to="/policies">
          All policies
        </Link>
      </div>
    </section>
  );
}
